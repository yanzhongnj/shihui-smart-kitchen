package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 做饭计划条目（哪天做哪道菜）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("cooking_plan_items")
public class CookingPlanItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Integer dayOffset;   // 周内第几天 0=周一
    private Long videoId;
    /** 关联 recipes.id（AI 菜单写入；手工条目为 NULL，v0.3） */
    private Long recipeId;
    private String recipeTitle;
    private String mealType;
    /** 荤素 meat/veg（AI 菜单带入，APP 徽标展示，v0.6） */
    private String dishType;
    /** appliance(线路A)|self(线路B)（v0.3） */
    private String execMode;
    /** 厨电时序编排结果（线路A，P1，v0.3） */
    private String scheduleJson;
    /** 份量系数 0.5/1.0/1.5（v0.3） */
    private BigDecimal portionScale;
    /** 份量是否已锁定（打卡后置1，v0.3） */
    private Integer portionLocked;
    /** 自动打卡关联 cooking_checkins.id（v0.3） */
    private Long checkinId;
    /** 自动饮食记录关联 diet_records.id（v0.3） */
    private Long dietRecordId;
    private Integer done;        // 0=未做 1=已跟做
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
