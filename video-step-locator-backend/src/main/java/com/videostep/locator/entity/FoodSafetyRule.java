package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 食物安全规则（food_safety_rules）。设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§5/§14。
 * 四类真实风险（MED 药物-食物 / PROC 加工安全 / IND 个体差异 / ABS 营养素干扰）+ MYTH 辟谣层。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("food_safety_rules")
public class FoodSafetyRule {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** MED|PROC|IND|ABS|MYTH（辟谣） */
    private String category;

    /** RED|YELLOW|GREEN（MYTH 为 GREEN 辟谣） */
    private String riskLevel;

    /** ingredient(食材)|combination(组合)|user_condition(用户条件) */
    private String triggerType;

    /** 触发值：食材名/组合/用户条件（逗号/斜杠分隔多值） */
    private String triggerValue;

    /** 关联食物（组合另一侧 / 忌口食物展示） */
    private String relatedFood;

    /** 规则标题 */
    private String title;

    /** 建议/提示文案（服务层附加免责声明） */
    private String advice;

    /** 权威出处（机构/资料类型） */
    private String source;

    /** 精确出处 URL（采集管道阶段回填） */
    private String sourceUrl;

    /** 置信度 1-100（L3 灰度，扩充集 50） */
    private Integer confidence;

    /** 规则版本（支持回滚） */
    private Integer version;

    /** 是否启用 */
    private Integer enabled;

    /** 软删除 0/1（MyBatis-Plus 全局逻辑删除） */
    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
