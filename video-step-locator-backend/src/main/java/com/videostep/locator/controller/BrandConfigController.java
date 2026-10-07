package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.BrandConfigService;
import com.videostep.locator.service.OrgAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * A1 白标化接口：公开品牌读取 + 企业维护。
 * GET /api/brand/{orgId} 公开（SecurityConfig permitAll）；企业维护走 /api/orgs/{orgId}/brand（owner/admin）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class BrandConfigController {

    private final BrandConfigService brandConfigService;
    private final OrgAccessService orgAccessService;

    /** 公开读取品牌配置（预装/设备激活页/未登录场景） */
    @GetMapping("/brand/{orgId}")
    public ApiResponse<Map<String, Object>> getBrand(@PathVariable Long orgId) {
        return ApiResponse.success(brandConfigService.getPublic(orgId));
    }

    /** 企业 owner/admin 维护品牌配置 */
    @PutMapping("/orgs/{orgId}/brand")
    public ApiResponse<Map<String, Object>> upsert(@RequestAttribute("userId") Long userId,
                                                   @PathVariable Long orgId,
                                                   @RequestBody Map<String, String> body) {
        orgAccessService.requireRole(userId, orgId, "admin");
        return ApiResponse.success(brandConfigService.upsert(orgId, body));
    }
}
