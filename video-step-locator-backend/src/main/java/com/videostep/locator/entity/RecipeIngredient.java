package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 菜谱食材明细表（工业型精确用量的权威落点）。
 * amount 为精确用量值（可空=定性）；tolerance 为误差±（可空）。
 * 设计依据：docs/拾味_菜谱来源体系与工业级精确化_设计文档.md（v0.2）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("recipe_ingredients")
public class RecipeIngredient {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recipeId;
    private Integer seq;
    private String name;
    /** 用量数值（可空=定性，如"适量"） */
    private BigDecimal amount;
    /** 单位：g/ml/个/勺/片/瓣/人份 */
    private String unit;
    /** 误差 ±（可空） */
    private BigDecimal tolerance;
    /** 是否主料 1/0 */
    private Integer isMain;
    private String note;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
