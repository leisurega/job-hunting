package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.getjobs.application.entity.JobWorkspaceEntity;
import com.getjobs.application.mapper.JobWorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 后台批量分析服务：负责周期性地将 PENDING 状态的 JD 提交给 AI 进行批量分析
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JobWorkspaceBatchAnalyzeService {

    private final JobWorkspaceMapper jobWorkspaceMapper;
    private final AiService aiService;

    private static final int BATCH_SIZE = 4;
    private static final int MAX_ATTEMPTS = 3;

    private volatile boolean isTableReady = false;

    /**
     * 每 30 秒扫描一次待处理任务
     */
    @Scheduled(fixedDelay = 30000)
    public void runBatchAnalysis() {
        // 0. 表就绪检查
        if (!isTableReady) {
            try {
                jobWorkspaceMapper.selectCount(new LambdaQueryWrapper<JobWorkspaceEntity>().last("LIMIT 1"));
                isTableReady = true;
            } catch (Exception e) {
                log.debug("job_workspace 表尚未就绪，跳过本次分析任务: {}", e.getMessage());
                return;
            }
        }

        // 0.1 自动触发历史数据回填检查 (每扫描前检查一次，不放在读接口里)
        try {
            // 这里需要注入 JobWorkspaceService 或者直接写逻辑，为了避免循环依赖，我们直接在 service 里提供一个静态或通过 context 获取的方法
            // 简单起见，我直接在这里写一个轻量检查
            checkAndTriggerBackfill();
        } catch (Exception e) {
            log.warn("检查回填任务失败: {}", e.getMessage());
        }

        // 1. 查询待分析的岗位
        LambdaQueryWrapper<JobWorkspaceEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.nested(w -> w.eq(JobWorkspaceEntity::getAnalysisStatus, "PENDING")
                                 .or()
                                 .eq(JobWorkspaceEntity::getAnalysisStatus, "PROCESSING") // 支持回填项
                                 .or()
                                 .nested(w2 -> w2.eq(JobWorkspaceEntity::getAnalysisStatus, "FAILED")
                                                 .lt(JobWorkspaceEntity::getAnalysisAttempt, MAX_ATTEMPTS)))
                    .orderByAsc(JobWorkspaceEntity::getCreatedAt)
                    .last("LIMIT " + BATCH_SIZE);

        List<JobWorkspaceEntity> pendingJobs = jobWorkspaceMapper.selectList(queryWrapper);

        if (pendingJobs.isEmpty()) {
            return;
        }

        log.info("发现 {} 条待 AI 分析的岗位，开始批量处理...", pendingJobs.size());

        // 2. 标记为 PROCESSING
        String batchNo = UUID.randomUUID().toString().substring(0, 8);
        for (JobWorkspaceEntity job : pendingJobs) {
            job.setAnalysisStatus("PROCESSING");
            job.setAnalysisBatchNo(batchNo);
            job.setAnalysisAttempt((job.getAnalysisAttempt() == null ? 0 : job.getAnalysisAttempt()) + 1);
            job.setUpdatedAt(LocalDateTime.now());
            jobWorkspaceMapper.updateById(job);
        }

        // 3. 调用 AI 批量分析
        try {
            String jsonResult = aiService.analyzeGapAndPlanBatch(pendingJobs);
            JSONArray array = new JSONArray(jsonResult);

            // 4. 解析结果并写回
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                long id = obj.optLong("id");
                String gap = obj.optString("gap");
                String plan = obj.optString("plan");
                int relevanceScore = obj.optInt("relevance_score", 0);
                String relevanceReason = obj.optString("relevance_reason");

                if (id > 0) {
                    JobWorkspaceEntity resultEntity = new JobWorkspaceEntity();
                    resultEntity.setId(id);
                    resultEntity.setAiGap(gap);
                    resultEntity.setAiPlan(plan);
                    resultEntity.setRelevanceScore(relevanceScore);
                    resultEntity.setRelevanceReason(relevanceReason);
                    resultEntity.setAnalysisStatus("DONE");
                    resultEntity.setAnalyzedAt(LocalDateTime.now());
                    resultEntity.setUpdatedAt(LocalDateTime.now());
                    resultEntity.setAnalysisError(null);
                    jobWorkspaceMapper.updateById(resultEntity);
                }
            }

            // 处理那些 AI 可能没返回的 ID (兜底)
            List<Long> requestedIds = pendingJobs.stream().map(JobWorkspaceEntity::getId).collect(Collectors.toList());
            for (int i = 0; i < array.length(); i++) {
                requestedIds.remove(array.getJSONObject(i).optLong("id"));
            }
            if (!requestedIds.isEmpty()) {
                for (Long missingId : requestedIds) {
                    markAsFailed(missingId, "AI 返回结果中缺失此岗位 ID");
                }
            }

            log.info("批次 {} 处理完成，成功分析 {} 条", batchNo, array.length());

        } catch (Exception e) {
            log.error("批次 {} 分析出现异常: {}", batchNo, e.getMessage());
            for (JobWorkspaceEntity job : pendingJobs) {
                markAsFailed(job.getId(), "分析异常: " + e.getMessage());
            }
        }
    }

    private void markAsFailed(Long id, String error) {
        JobWorkspaceEntity failedEntity = new JobWorkspaceEntity();
        failedEntity.setId(id);
        failedEntity.setAnalysisStatus("FAILED");
        failedEntity.setAnalysisError(error);
        failedEntity.setUpdatedAt(LocalDateTime.now());
        jobWorkspaceMapper.updateById(failedEntity);
    }

    private void checkAndTriggerBackfill() {
        LambdaQueryWrapper<JobWorkspaceEntity> query = new LambdaQueryWrapper<>();
        query.eq(JobWorkspaceEntity::getAnalysisStatus, "DONE")
             .and(w -> w.isNull(JobWorkspaceEntity::getRelevanceScore)
                        .or().eq(JobWorkspaceEntity::getRelevanceScore, 0)
                        .or().isNull(JobWorkspaceEntity::getRelevanceReason))
             .last("LIMIT 1");
        
        if (jobWorkspaceMapper.selectCount(query) > 0) {
            log.info("检测到历史分析记录缺失相关度数据，正在触发回填...");
            // 这里直接调用 update 语句把符合条件的 DONE 改为 PROCESSING
            JobWorkspaceEntity update = new JobWorkspaceEntity();
            update.setAnalysisStatus("PROCESSING");
            update.setAnalysisAttempt(0);
            update.setUpdatedAt(LocalDateTime.now());

            LambdaQueryWrapper<JobWorkspaceEntity> updateWrapper = new LambdaQueryWrapper<>();
            updateWrapper.eq(JobWorkspaceEntity::getAnalysisStatus, "DONE")
                         .and(w -> w.isNull(JobWorkspaceEntity::getRelevanceScore)
                                    .or().eq(JobWorkspaceEntity::getRelevanceScore, 0)
                                    .or().isNull(JobWorkspaceEntity::getRelevanceReason));
            
            jobWorkspaceMapper.update(update, updateWrapper);
        }
    }
}
