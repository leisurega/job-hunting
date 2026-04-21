package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.getjobs.application.entity.JobWorkspaceEntity;
import com.getjobs.application.event.CrawlCompletedEvent;
import com.getjobs.application.mapper.JobWorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ConfigService configService;

    private static final int DEFAULT_BATCH_SIZE = 8;
    private static final int MAX_ATTEMPTS = 3;

    private volatile boolean isTableReady = false;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int totalPending = 0;
    private int processedCount = 0;
    private final List<java.util.function.Consumer<JSONObject>> progressListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addProgressListener(java.util.function.Consumer<JSONObject> listener) {
        progressListeners.add(listener);
    }

    public void removeProgressListener(java.util.function.Consumer<JSONObject> listener) {
        progressListeners.remove(listener);
    }

    private void notifyProgress() {
        JSONObject progress = getProgress();
        progressListeners.forEach(l -> l.accept(progress));
    }

    private int getBatchSize() {
        try {
            String val = configService.getConfigValue("BATCH_SIZE");
            return val != null ? Integer.parseInt(val) : DEFAULT_BATCH_SIZE;
        } catch (Exception e) {
            return DEFAULT_BATCH_SIZE;
        }
    }

    /**
     * 响应抓取完成事件
     */
    @EventListener
    @Async("analyzeExecutor")
    public void onCrawlCompleted(CrawlCompletedEvent event) {
        log.info("收到 {} 平台抓取完成事件，共 {} 条，开始触发批量分析...", event.getPlatform(), event.getCount());
        runBatchAnalysis();
    }

    /**
     * 获取当前分析进度
     */
    public JSONObject getProgress() {
        JSONObject res = new JSONObject();
        res.put("running", running.get());
        res.put("done", processedCount);
        res.put("total", totalPending);
        return res;
    }

    /**
     * 批量分析任务
     */
    public void runBatchAnalysis() {
        if (!running.compareAndSet(false, true)) {
            log.info("分析任务已在运行中，跳过本次触发");
            return;
        }
        try {
            processedCount = 0;
            totalPending = countPendingJobs();
            notifyProgress();
            doAnalyze();
        } finally {
            running.set(false);
            notifyProgress();
        }
    }

    private int countPendingJobs() {
        LambdaQueryWrapper<JobWorkspaceEntity> query = new LambdaQueryWrapper<>();
        query.nested(w -> w.eq(JobWorkspaceEntity::getAnalysisStatus, "PENDING")
                         .or()
                         .eq(JobWorkspaceEntity::getAnalysisStatus, "PROCESSING")
                         .or()
                         .nested(w2 -> w2.eq(JobWorkspaceEntity::getAnalysisStatus, "FAILED")
                                         .lt(JobWorkspaceEntity::getAnalysisAttempt, MAX_ATTEMPTS)));
        return jobWorkspaceMapper.selectCount(query).intValue();
    }

    private void doAnalyze() {
        int batchSize = getBatchSize();
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

        // 0.1 自动触发历史数据回填检查
        try {
            checkAndTriggerBackfill();
        } catch (Exception e) {
            log.warn("检查回填任务失败: {}", e.getMessage());
        }

        while (true) {
            // 1. 查询待分析的岗位
            LambdaQueryWrapper<JobWorkspaceEntity> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.nested(w -> w.eq(JobWorkspaceEntity::getAnalysisStatus, "PENDING")
                                     .or()
                                     .eq(JobWorkspaceEntity::getAnalysisStatus, "PROCESSING") // 支持回填项
                                     .or()
                                     .nested(w2 -> w2.eq(JobWorkspaceEntity::getAnalysisStatus, "FAILED")
                                                     .lt(JobWorkspaceEntity::getAnalysisAttempt, MAX_ATTEMPTS)))
                        .orderByAsc(JobWorkspaceEntity::getCreatedAt)
                        .last("LIMIT " + batchSize);

            List<JobWorkspaceEntity> pendingJobs = jobWorkspaceMapper.selectList(queryWrapper);

            if (pendingJobs.isEmpty()) {
                break;
            }

            // 1.1 硬过滤规则预筛
            List<JobWorkspaceEntity> toAnalyze = new ArrayList<>();
            for (JobWorkspaceEntity job : pendingJobs) {
                if (isPreFiltered(job)) {
                    markAsSkipped(job.getId(), "命中硬过滤规则");
                    processedCount++;
                    continue;
                }
                toAnalyze.add(job);
            }

            if (toAnalyze.isEmpty()) {
                continue;
            }

            log.info("发现 {} 条待 AI 分析的岗位，开始批量处理...", toAnalyze.size());

            // 2. 标记为 PROCESSING
            String batchNo = UUID.randomUUID().toString().substring(0, 8);
            for (JobWorkspaceEntity job : toAnalyze) {
                job.setAnalysisStatus("PROCESSING");
                job.setAnalysisBatchNo(batchNo);
                job.setAnalysisAttempt((job.getAnalysisAttempt() == null ? 0 : job.getAnalysisAttempt()) + 1);
                job.setUpdatedAt(LocalDateTime.now());
                jobWorkspaceMapper.updateById(job);
            }

            // 3. 调用 AI 批量分析
            try {
                String jsonResult = aiService.analyzeGapAndPlanBatch(toAnalyze);
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
                List<Long> requestedIds = toAnalyze.stream().map(JobWorkspaceEntity::getId).collect(Collectors.toList());
                for (int i = 0; i < array.length(); i++) {
                    requestedIds.remove(array.getJSONObject(i).optLong("id"));
                }
                if (!requestedIds.isEmpty()) {
                    for (Long missingId : requestedIds) {
                        markAsFailed(missingId, "AI 返回结果中缺失此岗位 ID");
                    }
                }

                log.info("批次 {} 处理完成，成功分析 {} 条", batchNo, array.length());
                processedCount += array.length();
                notifyProgress();

            } catch (Exception e) {
                log.error("批次 {} 分析出现异常: {}", batchNo, e.getMessage());
                for (JobWorkspaceEntity job : toAnalyze) {
                    markAsFailed(job.getId(), "分析异常: " + e.getMessage());
                }
            }
        }
    }

    private boolean isPreFiltered(JobWorkspaceEntity job) {
        // 1. 薪资下限过滤
        try {
            String minSalaryVal = configService.getConfigValue("FILTER_MIN_SALARY");
            if (minSalaryVal != null && !minSalaryVal.isBlank()) {
                int minExpected = Integer.parseInt(minSalaryVal);
                BossService.SalaryInfo info = BossService.parseSalary(job.getSalary());
                if (info != null && info.maxK != null && info.maxK < minExpected) {
                    log.info("硬过滤：薪资上限 {} 低于期望下限 {}", info.maxK, minExpected);
                    return true;
                }
            }
        } catch (Exception ignore) {}

        // 2. 关键词黑名单过滤
        String blackKeywords = configService.getConfigValue("FILTER_BLACK_KEYWORDS");
        if (blackKeywords != null && !blackKeywords.isBlank()) {
            String[] keys = blackKeywords.split("[,，]");
            String content = (job.getJobName() + job.getCompanyName() + job.getJdText()).toLowerCase();
            for (String k : keys) {
                if (!k.isBlank() && content.contains(k.trim().toLowerCase())) {
                    log.info("硬过滤：命中关键词黑名单 '{}'", k);
                    return true;
                }
            }
        }

        return false;
    }

    private void markAsSkipped(Long id, String reason) {
        JobWorkspaceEntity skipped = new JobWorkspaceEntity();
        skipped.setId(id);
        skipped.setAnalysisStatus("SKIPPED");
        skipped.setAnalysisError(reason);
        skipped.setUpdatedAt(LocalDateTime.now());
        jobWorkspaceMapper.updateById(skipped);
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
