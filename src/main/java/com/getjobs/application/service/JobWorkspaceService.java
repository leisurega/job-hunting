package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.entity.Job51Entity;
import com.getjobs.application.entity.JobWorkspaceEntity;
import com.getjobs.application.entity.LiepinEntity;
import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.mapper.Job51Mapper;
import com.getjobs.application.mapper.JobWorkspaceMapper;
import com.getjobs.application.mapper.LiepinMapper;
import com.getjobs.application.mapper.ZhilianJobDataMapper;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.Job51JobService;
import com.getjobs.worker.service.LiepinJobService;
import com.getjobs.worker.service.ZhilianJobService;
import com.getjobs.worker.utils.JobUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 工作台服务：处理跨平台的 JD 分析、去重与存储
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JobWorkspaceService {

    private final Map<String, Long> lastCrawlAt = new java.util.concurrent.ConcurrentHashMap<>();

    private final JobWorkspaceMapper jobWorkspaceMapper;
    private final BossJobDataMapper bossJobDataMapper;
    private final LiepinMapper liepinMapper;
    private final Job51Mapper job51Mapper;
    private final ZhilianJobDataMapper zhilianJobDataMapper;
    private final AiService aiService;
    private final BossJobService bossJobService;
    private final LiepinJobService liepinJobService;
    private final Job51JobService job51JobService;
    private final ZhilianJobService zhilianJobService;

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
        return listJobs(platform, null, null, null, null, null, null, null);
    }

    /**
     * 获取岗位列表（支持筛选）
     */
    public List<JobWorkspaceEntity> listJobs(String platform, List<String> statuses, String location, 
                                             String experience, String degree, Double minK, Double maxK, String keyword) {
        LambdaQueryWrapper<JobWorkspaceEntity> wrapper = new LambdaQueryWrapper<>();
        if (platform != null && !platform.isEmpty()) {
            wrapper.eq(JobWorkspaceEntity::getPlatform, platform);
        }
        wrapper.orderByDesc(JobWorkspaceEntity::getCreatedAt);
        List<JobWorkspaceEntity> all = jobWorkspaceMapper.selectList(wrapper);
        enrichWorkspaceItems(all);

        return all.stream().filter(e -> {
            if (!isBlank(location) && !containsIgnoreCase(e.getLocation(), location)) return false;
            if (!isBlank(experience) && !containsIgnoreCase(e.getExperience(), experience)) return false;
            if (!isBlank(degree) && !containsIgnoreCase(e.getDegree(), degree)) return false;
            if (!isBlank(keyword)
                    && !containsIgnoreCase(e.getJobName(), keyword)
                    && !containsIgnoreCase(e.getCompanyName(), keyword)) {
                return false;
            }
            if (!matchesStatus(e, statuses)) return false;

            BossService.SalaryInfo info = BossService.parseSalary(e.getSalary());
            Double medianK = (info != null && info.medianK != null) ? info.medianK : null;
            if (minK != null && (medianK == null || medianK < minK)) return false;
            if (maxK != null && (medianK == null || medianK > maxK)) return false;
            return true;
        }).collect(Collectors.toList());
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

    /**
     * 获取投递分析统计与图表数据（按平台及筛选条件）
     */
    public BossService.StatsResponse getWorkspaceStats(String platform, List<String> statuses, String location, 
                                                       String experience, String degree, Double minK, Double maxK, String keyword) {
        BossService.StatsResponse resp = new BossService.StatsResponse();
        resp.kpi = new BossService.Kpi();
        BossService.Charts charts = new BossService.Charts();
        resp.charts = charts;

        // 确保所有图表列表都不为 null
        charts.byStatus = new ArrayList<>();
        charts.byCity = new ArrayList<>();
        charts.byIndustry = new ArrayList<>();
        charts.byCompany = new ArrayList<>();
        charts.byExperience = new ArrayList<>();
        charts.byDegree = new ArrayList<>();
        charts.salaryBuckets = new ArrayList<>();
        charts.dailyTrend = new ArrayList<>();
        charts.hrActivity = new ArrayList<>();
        List<JobWorkspaceEntity> filtered = listJobs(platform, statuses, location, experience, degree, minK, maxK, keyword);
        double sumMedian = 0.0;
        long countMedian = 0;
        List<Double> medians = new ArrayList<>();

        for (JobWorkspaceEntity e : filtered) {
            BossService.SalaryInfo info = BossService.parseSalary(e.getSalary());
            Double m = (info != null && info.medianK != null) ? info.medianK : null;
            if (m != null) {
                sumMedian += m;
                countMedian++;
                medians.add(m);
            }
        }

        resp.kpi.total = filtered.size();
        resp.kpi.delivered = filtered.stream().filter(e -> Objects.equals(resolveDisplayStatus(e), "已投递")).count();
        resp.kpi.pending = filtered.stream().filter(e -> Objects.equals(resolveDisplayStatus(e), "未投递")).count();
        resp.kpi.filtered = filtered.stream().filter(e -> Objects.equals(resolveDisplayStatus(e), "已忽略")).count();
        resp.kpi.failed = filtered.stream().filter(e -> Objects.equals(resolveDisplayStatus(e), "投递失败")).count();
        resp.kpi.avgMonthlyK = countMedian > 0 ? Math.round((sumMedian / countMedian) * 100.0) / 100.0 : 0.0;

        // byStatus
        Map<String, Long> statusMap = filtered.stream()
                .collect(Collectors.groupingBy(this::resolveDisplayStatus, Collectors.counting()));
        statusMap.forEach((k, v) -> charts.byStatus.add(new BossService.NameValue(k, v)));

        // byCity TOP10
        Map<String, Long> cityMap = filtered.stream()
                .collect(Collectors.groupingBy(e -> nullSafe(e.getLocation()), Collectors.counting()));
        cityMap.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byCity.add(new BossService.NameValue(en.getKey(), en.getValue())));

        // byIndustry TOP10
        Map<String, Long> industryMap = filtered.stream()
                .filter(e -> !isBlank(e.getIndustry()))
                .collect(Collectors.groupingBy(e -> nullSafe(e.getIndustry()), Collectors.counting()));
        industryMap.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byIndustry.add(new BossService.NameValue(en.getKey(), en.getValue())));

        // byCompany TOP10
        Map<String, Long> compMap = filtered.stream().collect(Collectors.groupingBy(e -> nullSafe(e.getCompanyName()), Collectors.counting()));
        compMap.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(10).forEach(en -> charts.byCompany.add(new BossService.NameValue(en.getKey(), en.getValue())));

        Map<String, Long> expMap = filtered.stream()
                .filter(e -> !isBlank(e.getExperience()))
                .collect(Collectors.groupingBy(e -> nullSafe(e.getExperience()), Collectors.counting()));
        expMap.forEach((k, v) -> charts.byExperience.add(new BossService.NameValue(k, v)));

        Map<String, Long> degreeMap = filtered.stream()
                .filter(e -> !isBlank(e.getDegree()))
                .collect(Collectors.groupingBy(e -> nullSafe(e.getDegree()), Collectors.counting()));
        degreeMap.forEach((k, v) -> charts.byDegree.add(new BossService.NameValue(k, v)));

        Map<String, Long> dayMap = filtered.stream()
                .filter(e -> e.getCreatedAt() != null)
                .collect(Collectors.groupingBy(e -> e.getCreatedAt().toLocalDate().toString(), Collectors.counting()));
        dayMap.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(en -> charts.dailyTrend.add(new BossService.NameValue(en.getKey(), en.getValue())));

        // salaryBuckets
        long b0_10=0, b10_15=0, b15_20=0, b20_top=0, b_ge_top=0;
        double maxMedian = 0.0;
        for (double m : medians) { if (m > maxMedian) maxMedian = m; }
        int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5;
        if (topEdge <= 20) topEdge = 25;
        for (double m : medians) {
            if (m < 10) b0_10++;
            else if (m < 15) b10_15++;
            else if (m < 20) b15_20++;
            else if (m < topEdge) b20_top++;
            else b_ge_top++;
        }
        charts.salaryBuckets.add(new BossService.BucketValue("0-10K", b0_10));
        charts.salaryBuckets.add(new BossService.BucketValue("10-15K", b10_15));
        charts.salaryBuckets.add(new BossService.BucketValue("15-20K", b15_20));
        charts.salaryBuckets.add(new BossService.BucketValue("20-" + topEdge + "K", b20_top));
        charts.salaryBuckets.add(new BossService.BucketValue(">=" + topEdge + "K", b_ge_top));

        return resp;
    }

    public Map<String, Object> refreshPlatform(String platform) {
        long start = System.currentTimeMillis();
        String normalizedPlatform = normalizePlatform(platform);

        // 风控降频：刷新后强制冷却 30 秒
        Long lastAt = lastCrawlAt.get(normalizedPlatform);
        if (lastAt != null && (start - lastAt) < 30000) {
            long remaining = 30 - (start - lastAt) / 1000;
            Map<String, Object> cooldown = new HashMap<>();
            cooldown.put("success", false);
            cooldown.put("message", String.format("冷却中，请 %d 秒后再试", remaining));
            cooldown.put("cooldown", true);
            return cooldown;
        }
        lastCrawlAt.put(normalizedPlatform, start);

        long before = countByPlatform(normalizedPlatform);
        
        // 发送抓取开始事件
        sendCrawlProgress(normalizedPlatform, "start", 0, 0, 0, 0);

        Map<String, Object> result = switch (normalizedPlatform) {
            case "boss" -> bossJobService.refreshFirstPage();
            case "liepin" -> liepinJobService.refreshFirstPage();
            case "51job" -> job51JobService.refreshFirstPage();
            case "zhilian" -> zhilianJobService.refreshFirstPage();
            default -> {
                Map<String, Object> unsupported = new HashMap<>();
                unsupported.put("success", false);
                unsupported.put("message", "不支持的平台: " + platform);
                yield unsupported;
            }
        };

        long after = countByPlatform(normalizedPlatform);
        long scanned = ((Number) result.getOrDefault("scannedCount", 0)).longValue();
        long added = Math.max(0, after - before);
        long skipped = Math.max(0, scanned - added);
        
        result.put("platform", normalizedPlatform);
        result.put("beforeTotal", before);
        result.put("afterTotal", after);
        result.put("fetchedCount", scanned);
        result.put("newCount", added);
        result.put("reusedCount", skipped);
        result.put("elapsedMs", System.currentTimeMillis() - start);

        // 发送抓取结束事件
        sendCrawlProgress(normalizedPlatform, "done", (int)scanned, (int)added, (int)skipped, (int)scanned);

        return result;
    }

    private final List<java.util.function.Consumer<String>> crawlProgressListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addCrawlProgressListener(java.util.function.Consumer<String> listener) {
        crawlProgressListeners.add(listener);
    }

    public void removeCrawlProgressListener(java.util.function.Consumer<String> listener) {
        crawlProgressListeners.remove(listener);
    }

    private void sendCrawlProgress(String platform, String state, int processed, int added, int skipped, int total) {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", "crawl");
        data.put("platform", platform);
        data.put("state", state); // start, processing, done
        data.put("processed", processed);
        data.put("added", added);
        data.put("skipped", skipped);
        data.put("total", total);
        data.put("timestamp", System.currentTimeMillis());
        
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
            crawlProgressListeners.forEach(l -> l.accept(json));
        } catch (Exception e) {
            log.error("序列化抓取进度失败", e);
        }
    }

    public void cancelCrawl(String platform) {
        if (isBlank(platform)) {
            bossJobService.stopDelivery();
            liepinJobService.stopDelivery();
            job51JobService.stopDelivery();
            zhilianJobService.stopDelivery();
        } else {
            switch (normalizePlatform(platform)) {
                case "boss" -> bossJobService.stopDelivery();
                case "liepin" -> liepinJobService.stopDelivery();
                case "51job" -> job51JobService.stopDelivery();
                case "zhilian" -> zhilianJobService.stopDelivery();
            }
        }
    }

    public Map<String, Object> deliverJobs(List<Long> ids) {
        Map<String, Object> resp = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "未选择岗位");
            return resp;
        }

        List<JobWorkspaceEntity> items = jobWorkspaceMapper.selectBatchIds(ids);
        Map<String, List<JobWorkspaceEntity>> grouped = items.stream()
                .collect(Collectors.groupingBy(item -> normalizePlatform(item.getPlatform())));

        List<Map<String, Object>> platformResults = new ArrayList<>();
        boolean allSuccess = true;
        for (Map.Entry<String, List<JobWorkspaceEntity>> entry : grouped.entrySet()) {
            String platform = entry.getKey();
            List<JobWorkspaceEntity> platformItems = entry.getValue();
            Map<String, Object> platformResult;
            switch (platform) {
                case "boss":
                    platformResult = bossJobService.deliverTargetJobs(platformItems.stream()
                            .map(JobWorkspaceEntity::getExternalId)
                            .filter(id -> !isBlank(id))
                            .collect(Collectors.toSet()));
                    break;
                case "liepin":
                    platformResult = liepinJobService.deliverTargetJobs(platformItems.stream()
                            .map(JobWorkspaceEntity::getExternalId)
                            .map(this::parseLong)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet()));
                    break;
                case "51job":
                    platformResult = job51JobService.deliverTargetJobs(platformItems.stream()
                            .map(JobWorkspaceEntity::getExternalId)
                            .map(this::parseLong)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet()));
                    break;
                case "zhilian":
                    platformResult = zhilianJobService.deliverTargetJobs(platformItems.stream()
                            .map(JobWorkspaceEntity::getExternalId)
                            .filter(id -> !isBlank(id))
                            .collect(Collectors.toSet()));
                    break;
                default:
                    platformResult = new HashMap<>();
                    platformResult.put("success", false);
                    platformResult.put("message", "不支持的平台: " + platform);
            }
            platformResult.put("platform", platform);
            platformResult.put("selectedCount", platformItems.size());
            platformResults.add(platformResult);
            if (!Boolean.TRUE.equals(platformResult.get("success"))) {
                allSuccess = false;
            }
        }

        resp.put("success", allSuccess);
        resp.put("message", allSuccess ? "批量投递任务执行完成" : "部分平台投递失败");
        resp.put("results", platformResults);
        resp.put("selectedCount", ids.size());
        return resp;
    }

    private void enrichWorkspaceItems(List<JobWorkspaceEntity> items) {
        if (items == null || items.isEmpty()) return;

        Map<String, List<JobWorkspaceEntity>> byPlatform = items.stream()
                .collect(Collectors.groupingBy(e -> e.getPlatform() == null ? "" : e.getPlatform().toLowerCase()));

        hydrateBoss(byPlatform.get("boss"));
        hydrateLiepin(byPlatform.get("liepin"));
        hydrateJob51(byPlatform.get("51job"));
        hydrateZhilian(byPlatform.get("zhilian"));
    }

    private void hydrateBoss(List<JobWorkspaceEntity> items) {
        if (items == null || items.isEmpty()) return;
        List<String> ids = items.stream().map(JobWorkspaceEntity::getExternalId).filter(id -> !isBlank(id)).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) return;
        QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
        wrapper.in("encrypt_id", ids);
        Map<String, BossJobDataEntity> map = bossJobDataMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(BossJobDataEntity::getEncryptId, e -> e, (a, b) -> a));
        for (JobWorkspaceEntity item : items) {
            BossJobDataEntity snapshot = map.get(item.getExternalId());
            if (snapshot == null) continue;
            item.setIndustry(snapshot.getIndustry());
            item.setExperience(snapshot.getExperience());
            item.setDegree(snapshot.getDegree());
            item.setSourceDeliveryStatus(snapshot.getDeliveryStatus());
        }
    }

    private void hydrateLiepin(List<JobWorkspaceEntity> items) {
        if (items == null || items.isEmpty()) return;
        List<Long> ids = items.stream().map(JobWorkspaceEntity::getExternalId).map(this::parseLong).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) return;
        QueryWrapper<LiepinEntity> wrapper = new QueryWrapper<>();
        wrapper.in("job_id", ids);
        Map<Long, LiepinEntity> map = liepinMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(LiepinEntity::getJobId, e -> e, (a, b) -> a));
        for (JobWorkspaceEntity item : items) {
            Long id = parseLong(item.getExternalId());
            LiepinEntity snapshot = id == null ? null : map.get(id);
            if (snapshot == null) continue;
            item.setIndustry(snapshot.getCompIndustry());
            item.setExperience(snapshot.getJobExpReq());
            item.setDegree(snapshot.getJobEduReq());
            item.setSourceDeliveryStatus(snapshot.getDelivered() != null && snapshot.getDelivered() == 1 ? "已投递" : "未投递");
        }
    }

    private void hydrateJob51(List<JobWorkspaceEntity> items) {
        if (items == null || items.isEmpty()) return;
        List<Long> ids = items.stream().map(JobWorkspaceEntity::getExternalId).map(this::parseLong).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) return;
        QueryWrapper<Job51Entity> wrapper = new QueryWrapper<>();
        wrapper.in("job_id", ids);
        Map<Long, Job51Entity> map = job51Mapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(Job51Entity::getJobId, e -> e, (a, b) -> a));
        for (JobWorkspaceEntity item : items) {
            Long id = parseLong(item.getExternalId());
            Job51Entity snapshot = id == null ? null : map.get(id);
            if (snapshot == null) continue;
            item.setIndustry(snapshot.getCompIndustry());
            item.setExperience(snapshot.getJobExpReq());
            item.setDegree(snapshot.getJobEduReq());
            item.setSourceDeliveryStatus(snapshot.getDelivered() != null && snapshot.getDelivered() == 1 ? "已投递" : "未投递");
        }
    }

    private void hydrateZhilian(List<JobWorkspaceEntity> items) {
        if (items == null || items.isEmpty()) return;
        List<String> ids = items.stream().map(JobWorkspaceEntity::getExternalId).filter(id -> !isBlank(id)).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) return;
        QueryWrapper<ZhilianJobDataEntity> wrapper = new QueryWrapper<>();
        wrapper.in("job_id", ids);
        Map<String, ZhilianJobDataEntity> map = zhilianJobDataMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(ZhilianJobDataEntity::getJobId, e -> e, (a, b) -> a));
        for (JobWorkspaceEntity item : items) {
            ZhilianJobDataEntity snapshot = map.get(item.getExternalId());
            if (snapshot == null) continue;
            item.setExperience(snapshot.getExperience());
            item.setDegree(snapshot.getDegree());
            item.setSourceDeliveryStatus(snapshot.getDeliveryStatus());
        }
    }

    private boolean matchesStatus(JobWorkspaceEntity item, List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return true;
        String actual = normalizeStatus(resolveDisplayStatus(item));
        for (String status : statuses) {
            if (Objects.equals(actual, normalizeStatus(status))) {
                return true;
            }
        }
        return false;
    }

    private String resolveDisplayStatus(JobWorkspaceEntity item) {
        String sourceStatus = normalizeStatus(item.getSourceDeliveryStatus());
        if (!isBlank(sourceStatus) && !"未投递".equals(sourceStatus)) {
            return sourceStatus;
        }
        if (item.getDeliveryStatus() != null) {
            switch (item.getDeliveryStatus()) {
                case 1:
                    return "已投递";
                case 2:
                    return "已忽略";
                default:
                    break;
            }
        }
        return isBlank(sourceStatus) ? "未投递" : sourceStatus;
    }

    private String normalizeStatus(String status) {
        if (isBlank(status)) return "";
        if ("待处理".equals(status)) return "未投递";
        return status.trim();
    }

    private boolean containsIgnoreCase(String raw, String expected) {
        return raw != null && expected != null && raw.toLowerCase().contains(expected.trim().toLowerCase());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nullSafe(String value) {
        return isBlank(value) ? "未知" : value;
    }

    private Long parseLong(String value) {
        if (isBlank(value)) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long countByPlatform(String platform) {
        LambdaQueryWrapper<JobWorkspaceEntity> wrapper = new LambdaQueryWrapper<>();
        if (!isBlank(platform)) {
            wrapper.eq(JobWorkspaceEntity::getPlatform, normalizePlatform(platform));
        }
        return jobWorkspaceMapper.selectCount(wrapper);
    }

    private String normalizePlatform(String platform) {
        return platform == null ? "" : platform.trim().toLowerCase();
    }
}
