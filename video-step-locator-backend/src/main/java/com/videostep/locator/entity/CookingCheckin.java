package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 炒菜打卡记录（Keep 式习惯记录）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("cooking_checkins")
public class CookingCheckin {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate checkinDate;
    private Long videoId;
    private String recipeTitle;
    private String mealType;
    private String mood;
    private String note;
    private Integer calories;
    private String source;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 逻辑删除：0 正常 / 1 已删除（软删除） */
    @TableLogic
    private Integer deleted;
    /** 软删除时间（回收站列表/恢复用） */
    private LocalDateTime deletedAt;
}
