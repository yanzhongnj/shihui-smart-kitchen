package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("api_usage_records")
public class ApiUsageRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long clientId;
    private String apiPath;
    private Long bytes;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
