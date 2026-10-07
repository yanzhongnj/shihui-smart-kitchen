package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 食材标签索引表（食材反查 P0）。
 * 设计文档《拾味_食材反查与内容源_技术设计文档.md》§6.1。
 * 由 IngredientIndexService 从 recipes.ingredients_json 重建（幂等）；
 * category 联动 nutrition_food_db.category。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ingredient_tags")
public class IngredientTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 食材名（归一化后） */
    private String name;
    /** 规范名（同义归并后） */
    private String canonicalName;
    /** 分类（联动 nutrition_food_db.category） */
    private String category;
    /** 出现频次（含该食材的菜谱数） */
    private Integer frequency;
    /** 是否仅主料维度出现 1/0 */
    private Integer isMainOnly;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
