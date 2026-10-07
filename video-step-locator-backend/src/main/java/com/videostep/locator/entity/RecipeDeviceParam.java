package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("recipe_device_params")
public class RecipeDeviceParam {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recipeId;
    private Integer stepIndex;
    private String deviceType;
    private String mode;
    private Integer temperature;
    private Integer durationMin;
    private String note;
    private String extra;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
