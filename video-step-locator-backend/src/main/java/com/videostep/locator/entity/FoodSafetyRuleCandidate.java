package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 食物安全规则候选/审核队列（采集管道：LLM 提取候选 + 人工审核后入库）。
 * 设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§1.2/§1.3。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("food_safety_rule_candidates")
public class FoodSafetyRuleCandidate {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** MED|PROC|IND|ABS|MYTH */
    private String category;

    /** RED|YELLOW|GREEN */
    private String riskLevel;

    /** ingredient|combination|user_condition */
    private String triggerType;

    /** 触发值 */
    private String triggerValue;

    /** 关联食物 */
    private String relatedFood;

    /** 规则标题 */
    private String title;

    /** 建议文案 */
    private String advice;

    /** 权威出处 */
    private String source;

    /** 精确出处 URL */
    private String sourceUrl;

    /** 原始资料摘要（溯源） */
    private String sourceText;

    /** LLM 原始返回（审计） */
    private String llmJson;

    /** pending|approved|rejected */
    private String status;

    /** 置信度 1-100 */
    private Integer confidence;

    private LocalDateTime reviewedAt;

    private Long reviewedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
