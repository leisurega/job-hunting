package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.JobWorkspaceEntity;
import com.getjobs.application.mapper.JobWorkspaceMapper;
import com.getjobs.worker.utils.JobUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台服务：处理跨平台的 JD 分析、去重与存储
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JobWorkspaceService {

    private final JobWorkspaceMapper jobWorkspaceMapper;
    private final AiService aiService;

    /**
     * 处理岗位数据：进行去重检查，若为新岗位则调用 AI 分析并入库
     * @param platform 平台
     * @param externalId 平台原生 ID
     * @param jobName 职位名称
     * @param companyName 公司名称
     * @param jdText JD 全文
     * @param jobUrl 岗位链接
     * @param salary 薪资
     * @param location 地点
     */
    @Transactional
    public void processJob(String platform, String externalId, String jobName, String companyName, 
                           String jdText, String jobUrl, String salary, String location) {
        
        // 1. 基础去重：检查该平台下的该 ID 是否已分析过
        LambdaQueryWrapper<JobWorkspaceEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(JobWorkspaceEntity::getPlatform, platform)
                    .eq(JobWorkspaceEntity::getExternalId, externalId);
        JobWorkspaceEntity existingById = jobWorkspaceMapper.selectOne(queryWrapper);
        if (existingById != null) {
            log.debug("跳过已存在岗位 (ID 去重): {} - {}", platform, externalId);
            return;
        }

        // 2. 内容指纹去重：检查该 JD 是否在其他平台/以其他 ID 出现过
        String fingerprint = JobUtils.md5(jdText);
        queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(JobWorkspaceEntity::getJdFingerprint, fingerprint).last("LIMIT 1");
        JobWorkspaceEntity existingByFingerprint = jobWorkspaceMapper.selectOne(queryWrapper);

        JobWorkspaceEntity entity = new JobWorkspaceEntity();
        entity.setPlatform(platform);
        entity.setExternalId(externalId);
        entity.setJobName(jobName);
        entity.setCompanyName(companyName);
        entity.setJdText(jdText);
        entity.setJdFingerprint(fingerprint);
        entity.setJobUrl(jobUrl);
        entity.setSalary(salary);
        entity.setLocation(location);
        entity.setDeliveryStatus(0); // 待处理
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        if (existingByFingerprint != null) {
            log.info("发现重复 JD (内容指纹去重): {} | 复用已有分析结果", jobName);
            entity.setAiGap(existingByFingerprint.getAiGap());
            entity.setAiPlan(existingByFingerprint.getAiPlan());
            entity.setAnalysisStatus("DONE");
            entity.setAnalyzedAt(LocalDateTime.now());
        } else {
            log.info("新岗位入库，标记为待分析: {} @ {}", jobName, companyName);
            entity.setAnalysisStatus("PENDING");
        }

        jobWorkspaceMapper.insert(entity);
    }

    /**
     * 获取待处理的分析列表
     */
    public List<JobWorkspaceEntity> listPendingJobs(String platform) {
        LambdaQueryWrapper<JobWorkspaceEntity> wrapper = new LambdaQueryWrapper<>();
        if (platform != null) {
            wrapper.eq(JobWorkspaceEntity::getPlatform, platform);
        }
        wrapper.orderByDesc(JobWorkspaceEntity::getCreatedAt);
        return jobWorkspaceMapper.selectList(wrapper);
    }

    /**
     * 更新投递状态
     */
    public boolean updateStatus(Long id, Integer status) {
        JobWorkspaceEntity entity = new JobWorkspaceEntity();
        entity.setId(id);
        entity.setDeliveryStatus(status);
        entity.setUpdatedAt(LocalDateTime.now());
        return jobWorkspaceMapper.updateById(entity) > 0;
    }

    /**
     * 删除岗位记录
     */
    @Transactional
    public boolean delete(Long id) {
        JobWorkspaceEntity entity = jobWorkspaceMapper.selectById(id);
        if (entity != null) {
            // 级联删除各平台的快照数据
            String platform = entity.getPlatform();
            String extId = entity.getExternalId();
            try {
                if ("boss".equalsIgnoreCase(platform)) {
                    QueryWrapper<com.getjobs.application.entity.BossJobDataEntity> qw = new QueryWrapper<>();
                    qw.eq("encrypt_id", extId);
                    // 这里需要注入对应的 Mapper 或 Service，为了简单起见，我们直接在各自的 Service 中处理级联
                }
            } catch (Exception e) {
                log.warn("级联删除平台数据失败: {} - {}", platform, extId);
            }
        }
        return jobWorkspaceMapper.deleteById(id) > 0;
    }

    /**
     * 批量删除岗位记录
     */
    @Transactional
    public boolean batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return true;
        return jobWorkspaceMapper.deleteBatchIds(ids) > 0;
    }

    /**
     * 回填逻辑：把历史上 DONE 但缺失相关度数据的记录置为 PROCESSING（而不是 PENDING），
     * 避免它们出现在前端的“待分析”列表里，同时又能被后台任务捡起。
     */
    @Transactional
    public void backfillLegacyRelevance() {
        LambdaQueryWrapper<JobWorkspaceEntity> query = new LambdaQueryWrapper<>();
        query.eq(JobWorkspaceEntity::getAnalysisStatus, "DONE")
             .and(w -> w.isNull(JobWorkspaceEntity::getRelevanceScore)
                        .or().eq(JobWorkspaceEntity::getRelevanceScore, 0)
                        .or().isNull(JobWorkspaceEntity::getRelevanceReason));
        
        List<JobWorkspaceEntity> legacyJobs = jobWorkspaceMapper.selectList(query);
        if (legacyJobs.isEmpty()) return;

        log.info("发现 {} 条历史分析记录缺失相关度数据，正在重置为 PROCESSING 以便后台回填...", legacyJobs.size());
        for (JobWorkspaceEntity job : legacyJobs) {
            JobWorkspaceEntity update = new JobWorkspaceEntity();
            update.setId(job.getId());
            // 改为 PROCESSING，这样前端过滤逻辑会把它当成“分析中”而不是“待分析”
            // 且后台任务需要支持捡起 PROCESSING 状态但没有 relevance 的数据
            update.setAnalysisStatus("PROCESSING");
            update.setAnalysisAttempt(0); 
            jobWorkspaceMapper.updateById(update);
        }
    }
}
