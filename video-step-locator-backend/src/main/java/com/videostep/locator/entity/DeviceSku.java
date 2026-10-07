package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("device_skus")
public class DeviceSku {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long vendorOrgId;
    private String sku;
    private String deviceType;
    private String capabilities;
    private String defaultRecipeGroup;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
