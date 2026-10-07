package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 回收站管理审计日志（P2）：记录恢复/彻底删除操作留痕。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("admin_audit_log")
public class AdminAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 操作管理员ID（系统任务为 NULL） */
    private Long operatorId;
    /** restore / purge */
    private String action;
    /** checkins / diet / plans / works / memories */
    private String targetType;
    private Long targetId;
    private String detail;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
