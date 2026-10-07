package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("device_bindings")
public class DeviceBinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skuId;
    private String bindCode;
    private Long vendorOrgId;
    private Long userId;
    /** unbound | bound | unbound_released */
    private String status;
    private LocalDateTime boundAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String STATUS_UNBOUND = "unbound";
    public static final String STATUS_BOUND = "bound";
}
