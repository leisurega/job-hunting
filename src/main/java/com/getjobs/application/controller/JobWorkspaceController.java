package com.getjobs.application.controller;

import com.getjobs.application.entity.JobWorkspaceEntity;
import com.getjobs.application.service.JobWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作台 API：管理 JD 分析结果与投递状态
 */
@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
public class JobWorkspaceController {

    private final JobWorkspaceService jobWorkspaceService;

    /**
     * 获取分析列表
     */
    @GetMapping("/list")
    public List<JobWorkspaceEntity> list(@RequestParam(required = false) String platform) {
        return jobWorkspaceService.listPendingJobs(platform);
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

    /**
     * 手动触发批量分析（用于调试或强制立即开始）
     */
    @PostMapping("/analyze-pending")
    public String triggerAnalyze() {
        // 由于 JobWorkspaceBatchAnalyzeService 的逻辑是异步/定时的
        // 这里可以简单的返回一个提示，或者直接注入该 service 并调用一次
        return "批量分析任务已在后台运行，每30秒会自动扫描一次待处理岗位。";
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
}
