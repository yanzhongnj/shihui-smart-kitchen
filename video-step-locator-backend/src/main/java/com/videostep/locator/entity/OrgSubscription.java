package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("org_subscriptions")
public class OrgSubscription {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String planCode;
    /** active | expired | canceled */
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime expiredAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
