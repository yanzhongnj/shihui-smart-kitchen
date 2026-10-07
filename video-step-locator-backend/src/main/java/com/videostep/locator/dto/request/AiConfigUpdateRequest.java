package com.videostep.locator.dto.request;

import lombok.Data;

import java.util.Map;

/**
 * AI 引擎配置更新请求。apiKey 为 null/空 = 保持不变（不覆盖已存密钥）。
 * extra 中未提供的字段 = 保持不变（合并式更新）。
 */
@Data
public class AiConfigUpdateRequest {
    private String displayName;
    private String endpoint;
    private String apiKey;
    private String model;
    private Map<String, String> extra;
    private Boolean mockEnabled;
    private Boolean enabled;

    /** 是否从环境变量采纳 api_key（未填 key 且 DB 无 key 时，把当前环境变量 key 固化到配置中心，不再依赖环境变量） */
    private Boolean adoptEnvKey;
}
