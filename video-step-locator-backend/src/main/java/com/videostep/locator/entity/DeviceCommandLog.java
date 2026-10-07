package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("device_command_logs")
public class DeviceCommandLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deviceId;
    private Long paramId;
    private String command;
    /** queued | sent | success | failed */
    private String status;
    private String result;
    private String errorMessage;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
}
