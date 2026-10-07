package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("kitchen_devices")
public class KitchenDevice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long orgId;
    /** tuya | mijia | mock */
    private String vendor;
    private String vendorDeviceId;
    private String nickname;
    private String deviceType;
    private String capabilities;
    private Integer online;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
