package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 饮食记录（跟做即记录 + 手动录入）。设计文档《拾味_营养健康模块_技术设计文档.md》§4.3。
 * checkin_id 弱关联打卡：打卡自动生成时非空，删除打卡级联删除。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("diet_records")
public class DietRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate recordDate;
    /** breakfast|lunch|dinner|snack */
    private String mealType;
    private Long recipeId;
    private Long checkinId;
    /** JSON 字符串 [{name,amount(g),calories,protein,fat,carbs}] */
    private String items;
    private BigDecimal calories;
    private BigDecimal protein;
    private BigDecimal fat;
    private BigDecimal carbs;
    /** 份量系数 0.5/1/1.5 */
    private BigDecimal portionFactor;
    /** 是否估算（菜谱无精确食材时） */
    private Integer estimated;
    /** cook(跟做)|manual|scan */
    private String source;
    private String note;
    /** 来源照片（拍照打卡时非空，关联 meal_photos.id） */
    private Long photoId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 逻辑删除：0 正常 / 1 已删除（软删除） */
    @TableLogic
    private Integer deleted;
    /** 软删除时间（回收站列表/恢复用） */
    private LocalDateTime deletedAt;

    public double cal() { return calories == null ? 0 : calories.doubleValue(); }
    public double protein() { return protein == null ? 0 : protein.doubleValue(); }
    public double fat() { return fat == null ? 0 : fat.doubleValue(); }
    public double carbs() { return carbs == null ? 0 : carbs.doubleValue(); }
}
