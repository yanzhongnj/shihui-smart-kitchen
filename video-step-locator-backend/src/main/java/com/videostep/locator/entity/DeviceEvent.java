package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("device_events")
public class DeviceEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deviceId;
    private Long vendorOrgId;
    private String eventType;
    private String payload;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
