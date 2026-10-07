package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("orgs")
public class Org {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private Long ownerId;
    private String logo;
    /** free | basic | pro | enterprise */
    private String planType;
    /** 0=禁用 1=正常 2=欠费停服 */
    private Integer status;
    /** 配额 JSON：video_limit/task_limit/storage_bytes/member_limit/download_limit */
    private String quota;
    private LocalDateTime expiredAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String PLAN_FREE = "free";
    public static final String PLAN_BASIC = "basic";
    public static final String PLAN_PRO = "pro";
    public static final String PLAN_ENTERPRISE = "enterprise";

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_OVERDUE = 2;
}
