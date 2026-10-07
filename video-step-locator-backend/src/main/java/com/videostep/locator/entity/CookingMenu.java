package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 菜单生成记录（cooking_menus，做饭计划执行闭环 P0，v0.3）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("cooking_menus")
public class CookingMenu {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 cooking_plans（确认后写入） */
    private Long planId;
    private Long userId;
    /** 菜单起始日（v0.4 多天可配） */
    private LocalDate menuWeek;
    /** 菜单天数（1~N，v0.4） */
    private Integer days;
    /** 计划标题（确认写入 cooking_plans.title，v0.4） */
    private String title;
    private String generatedBy;   // ai|manual
    /** 生成约束 JSON */
    private String constraintsJson;
    /** 条目 JSON [{day_offset, meal_type, dish_name, recipe_id, nutrition, note, device_type, dish_type}] */
    private String itemsJson;
    /** preview(预览)|confirmed(已写入计划)|discarded */
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
