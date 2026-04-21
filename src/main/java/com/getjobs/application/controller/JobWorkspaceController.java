package com.getjobs.application.controller;

import com.getjobs.application.entity.JobWorkspaceEntity;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.JobWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 工作台 API：管理 JD 分析结果与投递状态
 */
@Slf4j
@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
public class JobWorkspaceController {

    private final JobWorkspaceService jobWorkspaceService;
    private final com.getjobs.application.service.JobWorkspaceBatchAnalyzeService jobWorkspaceBatchAnalyzeService;
    private final com.getjobs.worker.manager.PlaywrightManager playwrightManager;

    /**
     * 获取分析列表
     */
    @GetMapping("/list")
    public List<JobWorkspaceEntity> list(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String statuses,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String experience,
            @RequestParam(required = false) String degree,
            @RequestParam(required = false) Double minK,
            @RequestParam(required = false) Double maxK,
            @RequestParam(required = false) String keyword
    ) {
        List<String> statusList = null;
        if (statuses != null && !statuses.isEmpty()) {
            statusList = java.util.Arrays.asList(statuses.split(","));
        }
        return jobWorkspaceService.listJobs(platform, statusList, location, experience, degree, minK, maxK, keyword);
    }

    /**
     * 获取统计数据
     */
    @GetMapping("/stats")
    public BossService.StatsResponse stats(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String statuses,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String experience,
            @RequestParam(required = false) String degree,
            @RequestParam(required = false) Double minK,
            @RequestParam(required = false) Double maxK,
            @RequestParam(required = false) String keyword
    ) {
        log.info("Requesting stats for platform: {}, keyword: {}, location: {}", platform, keyword, location);
        List<String> statusList = null;
        if (statuses != null && !statuses.isEmpty()) {
            statusList = java.util.Arrays.asList(statuses.split(","));
        }
        return jobWorkspaceService.getWorkspaceStats(platform, statusList, location, experience, degree, minK, maxK, keyword);
    }

    /**
     * 更新投递状态（1:已投递, 2:已忽略）
     */
    @PostMapping("/update-status")
    public boolean updateStatus(@RequestBody StatusUpdateRequest request) {
        return jobWorkspaceService.updateStatus(request.getId(), request.getStatus());
    }

    /**
     * 删除岗位记录
     */
    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return jobWorkspaceService.delete(id);
    }

    /**
     * 批量操作
     */
    @PostMapping("/batch-update-status")
    public boolean batchUpdateStatus(@RequestBody BatchStatusUpdateRequest request) {
        boolean allSuccess = true;
        for (Long id : request.getIds()) {
            if (!jobWorkspaceService.updateStatus(id, request.getStatus())) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    /**
     * 批量删除
     */
    @PostMapping("/batch-delete")
    public boolean batchDelete(@RequestBody List<Long> ids) {
        return jobWorkspaceService.batchDelete(ids);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody RefreshRequest request) {
        log.info("Refreshing platform: {}", request.getPlatform());
        return jobWorkspaceService.refreshPlatform(request.getPlatform());
    }

    @PostMapping("/crawl/cancel")
    public ResponseEntity<Map<String, Object>> cancelCrawl(@RequestBody Map<String, String> request) {
        String platform = request.get("platform");
        jobWorkspaceService.cancelCrawl(platform);
        return ResponseEntity.ok(Map.of("success", true, "message", "取消指令已发送"));
    }

    @GetMapping("/login-status")
    public ResponseEntity<Map<String, Object>> getLoginStatus() {
        Map<String, Boolean> status = Map.of(
            "boss", playwrightManager.isLoggedIn("boss"),
            "liepin", playwrightManager.isLoggedIn("liepin"),
            "51job", playwrightManager.isLoggedIn("51job"),
            "zhilian", playwrightManager.isLoggedIn("zhilian")
        );
        return ResponseEntity.ok(Map.of("success", true, "data", status));
    }

    @PostMapping("/open-login")
    public ResponseEntity<Map<String, Object>> openLogin(@RequestBody Map<String, String> request) {
        String platform = request.get("platform");
        try {
            switch (platform) {
                case "boss" -> playwrightManager.triggerBossLogin();
                case "liepin" -> playwrightManager.triggerLiepinLogin();
                case "51job" -> playwrightManager.trigger51jobLogin();
                case "zhilian" -> playwrightManager.triggerZhilianLogin();
                default -> throw new IllegalArgumentException("不支持的平台: " + platform);
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "已尝试打开登录页"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/deliver")
    public Map<String, Object> deliver(@RequestBody BatchDeliverRequest request) {
        return jobWorkspaceService.deliverJobs(request.getIds());
    }

    /**
     * 手动触发批量分析（用于调试或强制立即开始）
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> triggerAnalyze() {
        CompletableFuture.runAsync(jobWorkspaceBatchAnalyzeService::runBatchAnalysis);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "批量分析任务已启动"
        ));
    }

    /**
     * 获取分析进度
     */
    @GetMapping("/analyze/status")
    public ResponseEntity<String> getAnalyzeStatus() {
        return ResponseEntity.ok(jobWorkspaceBatchAnalyzeService.getProgress().toString());
    }

    /**
     * 手动触发批量分析（旧接口兼容）
     * @deprecated 请使用 /analyze
     */
    @Deprecated
    @PostMapping("/analyze-pending")
    public String triggerAnalyzeOld() {
        CompletableFuture.runAsync(jobWorkspaceBatchAnalyzeService::runBatchAnalysis);
        return "批量分析任务已在后台运行。";
    }

    // DTOs
    public static class StatusUpdateRequest {
        private Long id;
        private Integer status;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
    }

    public static class BatchStatusUpdateRequest {
        private List<Long> ids;
        private Integer status;
        public List<Long> getIds() { return ids; }
        public void setIds(List<Long> ids) { this.ids = ids; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
    }

    public static class RefreshRequest {
        private String platform;
        public String getPlatform() { return platform; }
        public void setPlatform(String platform) { this.platform = platform; }
    }

    public static class BatchDeliverRequest {
        private List<Long> ids;
        public List<Long> getIds() { return ids; }
        public void setIds(List<Long> ids) { this.ids = ids; }
    }
}
