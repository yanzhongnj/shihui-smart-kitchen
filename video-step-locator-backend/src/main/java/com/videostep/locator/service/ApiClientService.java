package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.ApiClient;
import com.videostep.locator.entity.ApiUsageRecord;
import com.videostep.locator.repository.ApiClientRepository;
import com.videostep.locator.repository.ApiUsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 开放平台（方向 C）：应用/密钥管理 + HMAC 签名校验 + 限流 + 调用计量。
 * 认证：X-App-Key + X-Timestamp + X-Nonce + X-Signature（HMAC-SHA256(secret, appKey+timestamp+nonce+body)）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiClientService {

    private final ApiClientRepository clientRepository;
    private final ApiUsageRecordRepository usageRepository;

    private static final long WINDOW_MS = 5 * 60 * 1000L;

    // ---------------- 应用管理 ----------------

    @Transactional
    public Map<String, Object> createClient(Long userId, Long orgId, String name, List<String> scopes) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("应用名不能为空");
        ApiClient c = ApiClient.builder()
                .orgId(orgId)
                .userId(userId)
                .appKey("AK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase())
                .appSecret("SK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 32).toUpperCase())
                .name(name.trim())
                .scopes(scopes == null || scopes.isEmpty() ? "[\"recipe\"]" : "[\"" + String.join("\",\"", scopes) + "\"]")
                .rateLimit(60)
                .status(1)
                .build();
        clientRepository.insert(c);
        return toMap(c);
    }

    public Map<String, Object> listMyClients(Long userId, Long orgId) {
        LambdaQueryWrapper<ApiClient> qw = new LambdaQueryWrapper<ApiClient>()
                .eq(orgId != null, ApiClient::getOrgId, orgId)
                .eq(orgId == null, ApiClient::getUserId, userId)
                .orderByDesc(ApiClient::getId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApiClient c : clientRepository.selectList(qw)) items.add(toMap(c));
        return Map.of("items", items, "total", (long) items.size());
    }

    public Map<String, Object> adminListClients(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<ApiClient> qw = new LambdaQueryWrapper<ApiClient>().orderByDesc(ApiClient::getId);
        long total = clientRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApiClient c : clientRepository.selectList(qw)) {
            Map<String, Object> m = toMap(c);
            m.put("usageTotal", usageRepository.selectCount(
                    new LambdaQueryWrapper<ApiUsageRecord>().eq(ApiUsageRecord::getClientId, c.getId())));
            items.add(m);
        }
        return Map.of("items", items, "total", total);
    }

    @Transactional
    public void revoke(Long userId, Long clientId) {
        ApiClient c = clientRepository.selectById(clientId);
        if (c == null) throw new IllegalArgumentException("应用不存在");
        if (!c.getUserId().equals(userId)) throw new SecurityException("无权操作该应用");
        c.setStatus(0);
        clientRepository.updateById(c);
    }

    // ---------------- 签名校验 ----------------

    /** 校验签名与限流；成功返回 client，失败抛异常 */
    public ApiClient verify(String appKey, String timestamp, String nonce, String signature, String body) {
        if (appKey == null || timestamp == null || nonce == null || signature == null) {
            throw new SecurityException("缺少签名头（X-App-Key / X-Timestamp / X-Nonce / X-Signature）");
        }
        ApiClient client = clientRepository.findByAppKey(appKey);
        if (client == null || client.getStatus() == null || client.getStatus() != 1) {
            throw new SecurityException("应用不存在或已禁用");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (Exception e) {
            throw new SecurityException("X-Timestamp 格式错误");
        }
        if (Math.abs(System.currentTimeMillis() - ts) > WINDOW_MS) {
            throw new SecurityException("请求时间戳过期");
        }
        String expected = hmac(client.getAppSecret(), appKey + timestamp + nonce + (body == null ? "" : body));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("签名校验失败");
        }
        long used = usageRepository.countLastMinute(client.getId());
        if (client.getRateLimit() != null && used >= client.getRateLimit()) {
            throw new RateLimitedException("请求超限（" + client.getRateLimit() + "/分钟）");
        }
        return client;
    }

    /** 记录调用计量 */
    public void recordUsage(Long clientId, String apiPath, long bytes, int status) {
        try {
            usageRepository.insert(ApiUsageRecord.builder()
                    .clientId(clientId).apiPath(apiPath).bytes(bytes).status(status).build());
        } catch (Exception e) {
            log.warn("记录开放平台用量失败: {}", e.getMessage());
        }
    }

    public static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) { super(message); }
    }

    private Map<String, Object> toMap(ApiClient c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("appKey", c.getAppKey());
        m.put("appSecret", c.getAppSecret());
        m.put("scopes", c.getScopes());
        m.put("rateLimit", c.getRateLimit());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }
}
