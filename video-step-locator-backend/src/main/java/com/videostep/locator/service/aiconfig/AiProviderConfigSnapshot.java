package com.videostep.locator.service.aiconfig;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.Map;

/**
 * 引擎配置运行快照（内存权威缓存条目）。设计文档 v0.3 §5。
 * apiKey 为解密后的明文（仅内存），接口响应使用 {@link #apiKeyMasked()}。
 */
@Data
@Builder
public class AiProviderConfigSnapshot {

    private String providerType;
    private String providerKey;
    private String displayName;
    /** API 地址（base-url）；空表示沿用环境变量默认 */
    private String endpoint;
    /** 解密后的明文 key（内存）；空表示沿用环境变量默认 */
    private String apiKey;
    private String model;
    /** 扩展参数（已合并 DB + 环境默认） */
    @Builder.Default
    private Map<String, String> extra = Collections.emptyMap();
    private boolean mockEnabled;
    private boolean enabled;

    public String getExtra(String key, String def) {
        String v = extra.get(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    public int getExtraInt(String key, int def) {
        String v = extra.get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return (int) Math.round(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public long getExtraLong(String key, long def) {
        String v = extra.get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Math.round(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public double getExtraDouble(String key, double def) {
        String v = extra.get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getExtraBool(String key, boolean def) {
        String v = extra.get(key);
        if (v == null || v.isBlank()) return def;
        return Boolean.parseBoolean(v.trim());
    }

    /** 脱敏展示：sk-***末4位 */
    public String apiKeyMasked() {
        if (apiKey == null || apiKey.isBlank()) return "";
        int n = apiKey.length();
        String tail = n > 4 ? apiKey.substring(n - 4) : apiKey;
        return "***" + tail;
    }
}
