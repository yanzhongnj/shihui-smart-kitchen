package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("payment_orders")
public class PaymentOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long invoiceId;
    private Long orgId;
    private Long userId;
    private BigDecimal amount;
    /** mock | alipay | wechat（当前模拟） */
    private String channel;
    /** created | success | failed | canceled */
    private String status;
    private String payNo;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;

    public static final String STATUS_CREATED = "created";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELED = "canceled";
}
