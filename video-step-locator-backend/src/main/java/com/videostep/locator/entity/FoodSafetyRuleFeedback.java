package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 食物安全规则用户反馈（反馈闭环：有用/无用/纠错 → 降级/人工复核）。
 * 设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§1.5。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("food_safety_rule_feedback")
public class FoodSafetyRuleFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** food_safety_rules.id */
    private Long ruleId;

    private Long userId;

    /** 1 有用 / 0 无用 */
    private Integer useful;

    /** 纠错/建议 */
    private String comment;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
