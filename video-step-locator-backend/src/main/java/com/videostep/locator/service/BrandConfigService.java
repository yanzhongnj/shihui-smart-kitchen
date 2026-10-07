package com.videostep.locator.service;

import com.videostep.locator.entity.BrandConfig;
import com.videostep.locator.repository.BrandConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A1 白标化：租户品牌配置（app_name/logo/theme/splash/about）。
 * - 公开读取：GET /api/brand/{orgId}（未登录设备/预装场景使用）；
 * - 维护：企业 owner/admin 或平台 Admin。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrandConfigService {

    private final BrandConfigRepository brandConfigRepository;

    /** 公开读取：返回品牌配置（无配置或未启用返回 null） */
    public Map<String, Object> getPublic(Long orgId) {
        BrandConfig c = brandConfigRepository.findByOrgId(orgId);
        if (c == null || (c.getEnabled() != null && c.getEnabled() == 0)) return null;
        return toMap(c);
    }

    /** 企业 owner/admin 维护（upsert） */
    @Transactional
    public Map<String, Object> upsert(Long orgId, Map<String, String> body) {
        BrandConfig c = brandConfigRepository.findByOrgId(orgId);
        if (c == null) {
            c = BrandConfig.builder().orgId(orgId).enabled(1).build();
        }
        apply(c, body);
        if (c.getId() == null) brandConfigRepository.insert(c);
        else brandConfigRepository.updateById(c);
        log.info("brand config upserted org={}", orgId);
        return toMap(c);
    }

    private void apply(BrandConfig c, Map<String, String> body) {
        if (body.containsKey("appName")) c.setAppName(body.get("appName"));
        if (body.containsKey("logoUrl")) c.setLogoUrl(emptyToNull(body.get("logoUrl")));
        if (body.containsKey("theme")) c.setTheme(emptyToNull(body.get("theme")));
        if (body.containsKey("splashText")) c.setSplashText(emptyToNull(body.get("splashText")));
        if (body.containsKey("aboutText")) c.setAboutText(emptyToNull(body.get("aboutText")));
        if (body.containsKey("enabled")) c.setEnabled("0".equals(body.get("enabled")) ? 0 : 1);
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Map<String, Object> toMap(BrandConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgId", c.getOrgId());
        m.put("appName", c.getAppName());
        m.put("logoUrl", c.getLogoUrl());
        m.put("theme", c.getTheme());
        m.put("splashText", c.getSplashText());
        m.put("aboutText", c.getAboutText());
        m.put("enabled", c.getEnabled());
        return m;
    }
}
