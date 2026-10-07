package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 健康档案（个性化营养目标，Mifflin-St Jeor TDEE）。
 * 设计文档《拾味_营养健康模块_技术设计文档.md》§4.1。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("health_profiles")
public class HealthProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 身高 cm */
    private BigDecimal height;
    /** 体重 kg */
    private BigDecimal weight;
    /** 目标体重 kg */
    private BigDecimal targetWeight;
    /** male|female */
    private String gender;
    /** 年龄 */
    private Integer age;
    /** sedentary|light|moderate|active|very_active */
    private String activityLevel;
    /** lose_weight|gain_muscle|maintain */
    private String goal;
    private Integer bmr;
    private Integer tdee;
    private Integer targetCalories;
    private Integer targetProtein;
    private Integer targetFat;
    private Integer targetCarbs;
    /** 用药情况（自愿填写，AES 密文，仅本人可见） */
    private String medications;
    /** 疾病情况（痛风/糖尿病/高血压/孕产等，自愿，AES 密文，仅本人可见） */
    private String conditions;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
