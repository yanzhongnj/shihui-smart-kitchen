package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 做饭计划（Keep 式训练计划：每周菜谱计划）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("cooking_plans")
public class CookingPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String planType;   // weekly/daily
    private LocalDate startDate;
    /** 来源菜单 cooking_menus.id（AI 生成时非空，v0.3） */
    private Long menuId;
    /** manual|ai（v0.3） */
    private String generatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 逻辑删除：0 正常 / 1 已删除（软删除） */
    @TableLogic
    private Integer deleted;
    /** 软删除时间（回收站列表/恢复用） */
    private LocalDateTime deletedAt;
}
