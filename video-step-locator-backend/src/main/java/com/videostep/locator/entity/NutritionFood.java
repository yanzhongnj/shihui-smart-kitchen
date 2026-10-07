package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 食物营养库（每 100g 营养）。设计文档《拾味_营养健康模块_技术设计文档.md》§4.2。
 * 核心 83 种为人工核对近似值；用户自定义/AI 回填以 source 区分，不做权威承诺。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("nutrition_food_db")
public class NutritionFood {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String nameEn;
    /** 主食/蔬菜/水果/肉类/海鲜/蛋奶/豆类/坚果/饮品/其他 */
    private String category;
    /** 热量 kcal/100g */
    private BigDecimal calories;
    /** 蛋白 g/100g */
    private BigDecimal protein;
    /** 脂肪 g/100g */
    private BigDecimal fat;
    /** 碳水 g/100g */
    private BigDecimal carbs;
    /** 纤维 g/100g */
    private BigDecimal fiber;
    /** 钠 mg/100g */
    private BigDecimal sodium;
    /** 是否内置常用（核心 83 种） 1/0 */
    private Integer isCore;
    /** seed|user|ai */
    private String source;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 便捷取数值（空 → 0） */
    public double cal() { return calories == null ? 0 : calories.doubleValue(); }
    public double protein() { return protein == null ? 0 : protein.doubleValue(); }
    public double fat() { return fat == null ? 0 : fat.doubleValue(); }
    public double carbs() { return carbs == null ? 0 : carbs.doubleValue(); }
    public double fiber() { return fiber == null ? 0 : fiber.doubleValue(); }
    public double sodium() { return sodium == null ? 0 : sodium.doubleValue(); }
}
