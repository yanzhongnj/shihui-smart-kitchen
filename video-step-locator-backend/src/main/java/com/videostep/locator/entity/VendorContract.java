package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("vendor_contracts")
public class VendorContract {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String contractNo;
    /** active | expired | suspended */
    private String status;
    private String contentScopes;
    private Integer deviceQuota;
    private LocalDateTime startedAt;
    private LocalDateTime expiredAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String STATUS_ACTIVE = "active";
}
