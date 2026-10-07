package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("org_plans")
public class OrgPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private BigDecimal priceMonthly;
    /** 配额模板 JSON：video_limit/task_limit/storage_bytes/member_limit/download_limit */
    private String quota;
    /** 功能开关 JSON：white_label/device/openapi */
    private String features;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
