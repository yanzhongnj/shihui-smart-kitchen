package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("usage_records")
public class UsageRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String deviceId;
    private String actionType;
    private Long videoId;
    private Long bytes;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
