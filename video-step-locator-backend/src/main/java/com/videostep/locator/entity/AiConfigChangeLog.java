package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * AI 配置变更日志（ai_config_change_logs）。设计文档 v0.3 §4.2。
 * before_json/after_json 中 api_key 一律打码（sk-***末4位），防审计表明文泄漏。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_config_change_logs")
public class AiConfigChangeLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerKey;

    /** create|update|enable|disable|restore_default|test */
    private String action;

    /** 变更前配置快照（api_key 已打码） */
    private String beforeJson;

    /** 变更后配置快照（api_key 已打码） */
    private String afterJson;

    /** 连通性测试结果（含耗时/错误信息） */
    private String testResult;

    /** 操作人（null=系统） */
    private Long operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
