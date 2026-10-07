package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 菜品级营养库（dish_nutrition_db）。设计文档《拾味_拍照打卡_技术设计文档.md》v0.2 §5.3。
 * 每行 = 一道菜的「每份」营养（serving_grams 默认 300g）；is_verified=1 表示人工复核过（种子/审核），0 为 AI 候选待审核。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("dish_nutrition_db")
public class DishNutrition {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 菜名（中文，唯一） */
    private String dishName;
    /** 别名 JSON（复用 food_aliases 归一后补充） */
    private String aliases;
    /** 菜系（川/粤/家常…，可选） */
    private String cuisine;
    /** 主要食材 JSON [{name, weight_g}] */
    private String mainIngredients;
    /** 每份营养 JSON {calories, protein, fat, carbs, fiber, sodium} */
    private String nutritionPerServing;
    /** 默认每份克数（份量估算基准） */
    private Integer servingGrams;
    /** 1=人工复核（种子/已审核）；0=AI 候选待审核 */
    private Integer isVerified;
    /** seed|ai|user */
    private String source;
    /** 命中次数（热度，驱动扩充排序） */
    private Integer hitCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除：0 正常 / 1 已删除（软删除进回收站，对齐 r114/r144 体系） */
    @TableLogic
    private Integer deleted;
    /** 软删除时间（回收站列表/恢复用） */
    private LocalDateTime deletedAt;
}
