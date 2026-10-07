package com.videostep.locator.service.aiconfig;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.AiProviderConfig;
import com.videostep.locator.repository.AiProviderConfigRepository;
import com.videostep.locator.service.aiconfig.AiConfigDefaults.ExtraFieldDef;
import com.videostep.locator.service.aiconfig.AiConfigDefaults.ProviderDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置中心内存权威缓存（v0.3 §5.1/§3.1）。
 * 启动/变更时由 DB 填充；DB 无值 → 按真值表回退链解析 env/yml（与 {@code @Value} 同一解析器，零风险迁移）。
 * 运行取值 = 本缓存快照；引擎经 refreshConfig 同步。
 */
@Component
@Slf4j
public class AiConfigCache {

    private final AiProviderConfigRepository repository;
    private final AiConfigCipher cipher;
    private final Environment environment;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, AiProviderConfigSnapshot> cache = new ConcurrentHashMap<>();
    private final Map<String, AiProviderConfig> rows = new ConcurrentHashMap<>();

    public AiConfigCache(AiProviderConfigRepository repository,
                         AiConfigCipher cipher,
                         Environment environment) {
        this.repository = repository;
        this.cipher = cipher;
        this.environment = environment;
    }

    /** 全量刷新：DB 行 + 回退链解析，写入缓存 */
    public synchronized void refreshAll() {
        rows.clear();
        for (AiProviderConfig r : repository.selectList(null)) {
            rows.put(r.getProviderKey(), r);
        }
        cache.clear();
        for (ProviderDef def : AiConfigDefaults.all()) {
            cache.put(def.key, resolve(def, rows.get(def.key)));
        }
        log.info("[AiConfigCache] 配置中心缓存已加载 {} 个引擎配置", cache.size());
    }

    /** 刷新单个引擎配置（DB 变更后调用） */
    public synchronized AiProviderConfigSnapshot refresh(String providerKey) {
        ProviderDef def = AiConfigDefaults.def(providerKey);
        if (def == null) {
            return cache.get(providerKey);
        }
        AiProviderConfig row = repository.selectOne(
                new LambdaQueryWrapper<AiProviderConfig>().eq(AiProviderConfig::getProviderKey, providerKey));
        if (row != null) {
            rows.put(providerKey, row);
        } else {
            rows.remove(providerKey);
        }
        AiProviderConfigSnapshot snap = resolve(def, row);
        cache.put(providerKey, snap);
        return snap;
    }

    public AiProviderConfigSnapshot get(String providerKey) {
        return cache.get(providerKey);
    }

    public AiProviderConfig row(String providerKey) {
        return rows.get(providerKey);
    }

    /** 引擎路由值（video_gen/image_gen/asr 的 engine 选择） */
    public String engineRoute(String providerKey) {
        AiProviderConfigSnapshot snap = cache.get(providerKey);
        ProviderDef def = AiConfigDefaults.def(providerKey);
        String defVal = def != null && !def.extra.isEmpty()
                ? env(def.extra.get(0).ymlPath, "")
                : "";
        if (snap == null) return defVal;
        return snap.getExtra("engine", defVal);
    }

    private AiProviderConfigSnapshot resolve(ProviderDef def, AiProviderConfig row) {
        Map<String, String> dbExtra = parseExtra(row);
        Map<String, String> extra = new LinkedHashMap<>();
        if (def.extra != null) {
            for (ExtraFieldDef f : def.extra) {
                String dbVal = dbExtra.get(f.key);
                extra.put(f.key, dbVal != null ? dbVal : env(f.ymlPath, ""));
            }
        }

        String apiKey = "";
        if (row != null && row.getApiKey() != null && !row.getApiKey().isBlank()) {
            String dec = cipher.decrypt(row.getApiKey());
            if (dec != null) {
                apiKey = dec;
            } else {
                log.warn("[AiConfigCache] {} api_key 解密失败（可能主钥变更），回退环境变量", def.key);
            }
        }
        if (apiKey.isBlank() && def.apiKeyYml != null) {
            apiKey = env(def.apiKeyYml, "");
        }

        String endpoint = row != null && row.getEndpoint() != null ? row.getEndpoint()
                : (def.endpointYml != null ? env(def.endpointYml, "") : "");
        String model = row != null && row.getModel() != null ? row.getModel()
                : (def.modelYml != null ? env(def.modelYml, "") : "");
        boolean mock = row != null && row.getMockEnabled() != null
                ? row.getMockEnabled() == 1
                : (def.mockYml != null && Boolean.parseBoolean(env(def.mockYml, "false")));
        boolean enabled = row != null && row.getEnabled() != null ? row.getEnabled() == 1 : true;

        return AiProviderConfigSnapshot.builder()
                .providerType(def.type)
                .providerKey(def.key)
                .displayName(row != null && row.getDisplayName() != null ? row.getDisplayName() : def.displayName)
                .endpoint(endpoint)
                .apiKey(apiKey)
                .model(model)
                .extra(extra)
                .mockEnabled(mock)
                .enabled(enabled)
                .build();
    }

    private Map<String, String> parseExtra(AiProviderConfig row) {
        Map<String, String> map = new LinkedHashMap<>();
        if (row == null || row.getExtra() == null || row.getExtra().isBlank()) {
            return map;
        }
        try {
            map = objectMapper.readValue(row.getExtra(), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            log.warn("[AiConfigCache] 解析 extra JSON 失败 providerKey={}: {}", row.getProviderKey(), e.getMessage());
        }
        return map != null ? map : new LinkedHashMap<>();
    }

    private String env(String path, String def) {
        if (path == null || path.isBlank()) return def;
        String v = environment.getProperty(path);
        return v != null ? v : def;
    }

    /** 返回全部 providerKey（供 UI 分组渲染） */
    public List<String> providerKeys() {
        return new java.util.ArrayList<>(cache.keySet());
    }
}
