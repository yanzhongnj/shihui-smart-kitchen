package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * AI 配置系统级设置（ai_config_settings）。设计文档 v0.3 §4.3。
 * 现用于存储加密总钥匙 encryption.master_key（启动定死、UI 不提供更换）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_config_settings")
public class AiConfigSetting {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 如 encryption.master_key */
    private String configKey;

    /** 值（总钥匙为明文字符串，见 v0.3 §5.3 威胁模型） */
    private String configValue;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
