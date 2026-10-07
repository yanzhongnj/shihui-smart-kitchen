package com.videostep.locator.service.aiconfig;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.dto.request.AiConfigUpdateRequest;
import com.videostep.locator.entity.AiConfigChangeLog;
import com.videostep.locator.entity.AiProviderConfig;
import com.videostep.locator.entity.User;
import com.videostep.locator.repository.AiConfigChangeLogRepository;
import com.videostep.locator.repository.AiProviderConfigRepository;
import com.videostep.locator.repository.UserRepository;
import com.videostep.locator.service.aiconfig.AiConfigDefaults.ProviderDef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 配置中心服务（v0.3 §5.1/§5.5/§6）：CRUD + 热更新 + 恢复默认 + 连通性测试 + 变更日志。
 * 变更流程：落库（api_key AES）→ 写日志（打码）→ 刷新缓存 → 推 refreshConfig → 引擎下次调用用新值。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiConfigService {

    private final AiProviderConfigRepository repository;
    private final AiConfigChangeLogRepository logRepository;
    private final UserRepository userRepository;
    private final AiConfigCache cache;
    private final AiProviderRegistry registry;
    private final AiConfigCipher cipher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------- 查询 ----------------

    /** 引擎配置列表（按 provider_type 分组，key 脱敏） */
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : cache.providerKeys()) {
            AiProviderConfigSnapshot snap = cache.get(key);
            if (snap != null) {
                items.add(view(snap));
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("total", items.size());
        return r;
    }

    /** 单引擎详情（脱敏） */
    public Map<String, Object> detail(String providerKey) {
        AiProviderConfigSnapshot snap = cache.get(providerKey);
        if (snap == null) {
            throw new IllegalArgumentException("未知引擎配置: " + providerKey);
        }
        return view(snap);
    }

    // ---------------- 写操作 ----------------

    /** 更新配置（热生效）。apiKey 留空=不变；extra 合并式更新 */
    public Map<String, Object> update(String providerKey, AiConfigUpdateRequest req, Long operator) {
        ProviderDef def = AiConfigDefaults.def(providerKey);
        if (def == null) {
            throw new IllegalArgumentException("未知引擎配置: " + providerKey);
        }
        AiProviderConfig row = select(providerKey);
        boolean isNew = row == null;
        Map<String, Object> before = row != null ? toMaskedMap(row) : null;

        if (isNew) {
            row = AiProviderConfig.builder()
                    .providerKey(providerKey)
                    .providerType(def.type)
                    .build();
        }
        if (req.getDisplayName() != null) row.setDisplayName(req.getDisplayName());
        if (req.getEndpoint() != null) row.setEndpoint(req.getEndpoint());
        if (req.getModel() != null) row.setModel(req.getModel());
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            row.setApiKey(cipher.encrypt(req.getApiKey()));
        }
        // apiKey 未填：要求采纳环境变量（或新行）且当前无 DB key 且环境变量有 key → 固化为 DB 值（不再依赖环境变量）
        boolean noTypedKey = req.getApiKey() == null || req.getApiKey().isBlank();
        boolean needAdopt = noTypedKey
                && (Boolean.TRUE.equals(req.getAdoptEnvKey()) || isNew)
                && (row.getApiKey() == null || row.getApiKey().isBlank());
        if (needAdopt) {
            AiProviderConfigSnapshot cur = cache.get(providerKey);
            if (cur != null && cur.getApiKey() != null && !cur.getApiKey().isBlank()) {
                row.setApiKey(cipher.encrypt(cur.getApiKey()));
            }
        }
        if (req.getMockEnabled() != null) row.setMockEnabled(req.getMockEnabled() ? 1 : 0);
        if (req.getEnabled() != null) row.setEnabled(req.getEnabled() ? 1 : 0);
        row.setUpdatedBy(operator);

        // extra 合并式更新（null 值 = 删除该键）
        Map<String, String> extra = parseExtra(row);
        if (req.getExtra() != null) {
            for (Map.Entry<String, String> en : req.getExtra().entrySet()) {
                if (en.getValue() == null) {
                    extra.remove(en.getKey());
                } else {
                    extra.put(en.getKey(), en.getValue());
                }
            }
        }
        row.setExtra(toJson(extra));

        if (isNew) {
            repository.insert(row);
        } else {
            repository.updateById(row);
        }

        AiProviderConfigSnapshot snap = cache.refresh(providerKey);
        registry.notify(providerKey, snap);
        writeLog(providerKey, isNew ? "create" : "update", before, toMaskedMap(row), null, operator);
        log.info("[AiConfig] {} {} (operator={})", isNew ? "create" : "update", providerKey, operator);
        return view(snap);
    }

    /** 恢复环境变量默认（删除 DB 行） */
    public Map<String, Object> restoreDefault(String providerKey, Long operator) {
        AiProviderConfig row = select(providerKey);
        if (row != null) {
            Map<String, Object> before = toMaskedMap(row);
            repository.deleteById(row.getId());
            cache.refresh(providerKey);
            registry.notify(providerKey, cache.get(providerKey));
            writeLog(providerKey, "restore_default", before, null, null, operator);
            log.info("[AiConfig] restore_default {} (operator={})", providerKey, operator);
        }
        return detail(providerKey);
    }

    /** 启用/停用 */
    public Map<String, Object> setEnabled(String providerKey, boolean enabled, Long operator) {
        AiProviderConfig row = select(providerKey);
        Map<String, Object> before = row != null ? toMaskedMap(row) : null;
        if (row == null) {
            ProviderDef def = AiConfigDefaults.def(providerKey);
            row = AiProviderConfig.builder()
                    .providerKey(providerKey)
                    .providerType(def != null ? def.type : "llm")
                    .build();
        }
        row.setEnabled(enabled ? 1 : 0);
        row.setUpdatedBy(operator);
        if (row.getId() != null) {
            repository.updateById(row);
        } else {
            repository.insert(row);
        }
        AiProviderConfigSnapshot snap = cache.refresh(providerKey);
        registry.notify(providerKey, snap);
        writeLog(providerKey, enabled ? "enable" : "disable", before, toMaskedMap(row), null, operator);
        return view(snap);
    }

    /** 连通性测试（视频/文生图仅校验鉴权，不真提交） */
    public Map<String, Object> test(String providerKey, Long operator) {
        long start = System.currentTimeMillis();
        boolean ok = registry.test(providerKey);
        long ms = System.currentTimeMillis() - start;
        String result = (ok ? "ok" : "fail") + " (" + ms + "ms)";

        AiProviderConfig row = select(providerKey);
        if (row != null) {
            row.setStatus(ok ? "healthy" : "unhealthy");
            row.setUpdatedBy(operator);
            repository.updateById(row);
        }
        writeLog(providerKey, "test", null, null, result, operator);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("providerKey", providerKey);
        r.put("ok", ok);
        r.put("result", result);
        r.put("elapsedMs", ms);
        return r;
    }

    /** 全部引擎健康快照 */
    public Map<String, Object> healthAll() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : cache.providerKeys()) {
            long start = System.currentTimeMillis();
            boolean ok = registry.test(key);
            long ms = System.currentTimeMillis() - start;
            AiProviderConfig row = select(key);
            if (row != null) {
                row.setStatus(ok ? "healthy" : "unhealthy");
                repository.updateById(row);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("providerKey", key);
            m.put("ok", ok);
            m.put("elapsedMs", ms);
            m.put("status", row != null ? row.getStatus() : (ok ? "healthy" : "unhealthy"));
            items.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        return r;
    }

    /** 变更日志（审计） */
    public List<Map<String, Object>> changeLogs(String providerKey) {
        LambdaQueryWrapper<AiConfigChangeLog> qw = new LambdaQueryWrapper<>();
        if (providerKey != null && !providerKey.isBlank()) {
            qw.eq(AiConfigChangeLog::getProviderKey, providerKey);
        }
        qw.orderByDesc(AiConfigChangeLog::getId).last("LIMIT 500");
        return logRepository.selectList(qw).stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("providerKey", l.getProviderKey());
            m.put("action", l.getAction());
            m.put("beforeJson", l.getBeforeJson());
            m.put("afterJson", l.getAfterJson());
            m.put("testResult", l.getTestResult());
            m.put("operator", l.getOperator());
            m.put("operatorName", operatorName(l.getOperator()));
            m.put("createdAt", l.getCreatedAt());
            return m;
        }).toList();
    }

    /** 操作人显示名：昵称优先，其次用户名；null=系统（不显示 ID） */
    private String operatorName(Long operator) {
        if (operator == null) return "系统";
        User u = userRepository.selectById(operator);
        if (u == null) return "#" + operator;
        String name = u.getNickname();
        if (name == null || name.isBlank()) name = u.getUsername();
        return name;
    }

    // ---------------- 工具 ----------------

    private AiProviderConfig select(String providerKey) {
        return repository.selectOne(
                new LambdaQueryWrapper<AiProviderConfig>().eq(AiProviderConfig::getProviderKey, providerKey));
    }

    /** 响应视图（api_key 脱敏，不含明文） */
    private Map<String, Object> view(AiProviderConfigSnapshot snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providerType", snap.getProviderType());
        m.put("providerKey", snap.getProviderKey());
        m.put("displayName", snap.getDisplayName());
        m.put("endpoint", snap.getEndpoint());
        m.put("apiKeyMasked", snap.apiKeyMasked());
        m.put("hasApiKey", snap.getApiKey() != null && !snap.getApiKey().isBlank());
        m.put("model", snap.getModel());
        m.put("extra", snap.getExtra());
        m.put("mockEnabled", snap.isMockEnabled());
        m.put("enabled", snap.isEnabled());
        AiProviderConfig row = cache.row(snap.getProviderKey());
        m.put("inDb", row != null);
        m.put("status", row != null ? row.getStatus() : "untested");
        m.put("updatedAt", row != null ? row.getUpdatedAt() : null);
        return m;
    }

    /** 日志快照（api_key 打码） */
    private Map<String, Object> toMaskedMap(AiProviderConfig row) {
        if (row == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providerKey", row.getProviderKey());
        m.put("endpoint", row.getEndpoint());
        m.put("model", row.getModel());
        String key = row.getApiKey();
        m.put("apiKey", key != null && !key.isBlank() ? "***" : null);
        m.put("extra", row.getExtra());
        m.put("mockEnabled", row.getMockEnabled());
        m.put("enabled", row.getEnabled());
        return m;
    }

    private void writeLog(String providerKey, String action, Map<String, Object> before,
                          Map<String, Object> after, String testResult, Long operator) {
        AiConfigChangeLog l = AiConfigChangeLog.builder()
                .providerKey(providerKey)
                .action(action)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .testResult(testResult)
                .operator(operator)
                .build();
        logRepository.insert(l);
    }

    private Map<String, String> parseExtra(AiProviderConfig row) {
        if (row.getExtra() == null || row.getExtra().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> m = objectMapper.readValue(row.getExtra(),
                    new TypeReference<LinkedHashMap<String, String>>() {});
            return m != null ? m : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("[AiConfig] 解析 extra 失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[AiConfig] JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
