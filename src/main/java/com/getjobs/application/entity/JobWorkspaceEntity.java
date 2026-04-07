package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * job_workspace 表实体，用于全平台 JD 分析与去重
 */
@Data
@TableName("job_workspace")
public class JobWorkspaceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("platform")
    private String platform; // boss / liepin / job51 / zhilian

    @TableField("external_id")
    private String externalId; // 平台原生唯一 ID

    @TableField("job_url")
    private String jobUrl; // 岗位链接

    @TableField("job_name")
    private String jobName; // 职位名称

    @TableField("company_name")
    private String companyName; // 公司名称

    @TableField("salary")
    private String salary; // 薪资

    @TableField("location")
    private String location; // 地点

    @TableField("jd_text")
    private String jdText; // JD 全文

    @TableField("jd_fingerprint")
    private String jdFingerprint; // JD 内容的 MD5 指纹 (全局唯一)

    @TableField("ai_gap")
    private String aiGap; // AI 分析的简历差距

    @TableField("ai_plan")
    private String aiPlan; // AI 建议的跟进计划

    @TableField("delivery_status")
    private Integer deliveryStatus; // 0:待处理, 1:已投递, 2:已忽略

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("analysis_status")
    private String analysisStatus; // PENDING / PROCESSING / DONE / FAILED

    @TableField("analysis_error")
    private String analysisError; // 最近一次失败原因

    @TableField("analysis_batch_no")
    private String analysisBatchNo; // 批次号

    @TableField("analysis_attempt")
    private Integer analysisAttempt; // 重试次数

    @TableField("analyzed_at")
    private LocalDateTime analyzedAt; // 分析完成时间

    @TableField("relevance_score")
    private Integer relevanceScore; // 相关性打分 (0-100)

    @TableField("relevance_reason")
    private String relevanceReason; // 打分理由
}
