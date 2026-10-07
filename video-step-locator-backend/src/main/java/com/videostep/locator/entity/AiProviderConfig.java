package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * AI 引擎配置（ai_provider_configs）。设计文档《拾味_AI模型配置中心_技术设计文档_v0.3.md》§4.1。
 * provider_type 六类：llm|vision|video_gen|image_gen|asr|tts。
 * api_key 由 AiConfigCipher AES 加密存储（接口响应脱敏）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_provider_configs")
public class AiProviderConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** llm|vision|video_gen|image_gen|asr|tts */
    private String providerType;

    /** llm.xunwei / vision.qwen-vl / video_gen.wan / video_gen.seedance / image_gen.wan_image / asr.aliyun / asr.funasr / tts.qwen3 */
    private String providerKey;

    /** 展示名（如 通义万相 Wan / 语音讲解 qwen3-tts） */
    private String displayName;

    /** API 地址（base-url） */
    private String endpoint;

    /** 密钥（AES 加密存储） */
    private String apiKey;

    /** 模型名 */
    private String model;

    /** 扩展参数 JSON {temperature,timeout_ms,size,voice,format,sample_rate,query_endpoint,engine,host,port,resolution,style,...} */
    private String extra;

    /** 统一 mock 开关（对应各引擎独立 mock 标志） */
    private Integer mockEnabled;

    /** 是否默认启用 */
    @TableField("is_default")
    private Integer isDefault;

    /** 是否启用 */
    private Integer enabled;

    /** untested|healthy|unhealthy */
    private String status;

    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
