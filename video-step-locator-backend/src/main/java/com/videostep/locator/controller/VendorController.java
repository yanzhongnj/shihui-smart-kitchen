package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.OrgAccessService;
import com.videostep.locator.service.VendorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * A2 厂商/设备接口：
 * - POST /api/device-bindings/activate：C 端用户扫码激活设备（登录态）；
 * - GET /api/orgs/{orgId}/vendor/**：厂商成员查看自己设备的绑定/激活统计/内容授权。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class VendorController {

    private final VendorService vendorService;
    private final OrgAccessService orgAccessService;

    /** C 端用户激活出厂绑定码（绑定设备到当前用户） */
    @PostMapping("/device-bindings/activate")
    public ApiResponse<Map<String, Object>> activate(@RequestAttribute("userId") Long userId,
                                                     @RequestBody Map<String, String> body) {
        return ApiResponse.success(vendorService.activate(userId, body.get("bindCode")));
    }

    /** 厂商成员：激活统计 */
    @GetMapping("/orgs/{orgId}/vendor/activation-stats")
    public ApiResponse<Map<String, Object>> activationStats(@RequestAttribute("userId") Long userId,
                                                            @PathVariable Long orgId) {
        orgAccessService.requireMember(userId, orgId);
        return ApiResponse.success(vendorService.activationStats(orgId));
    }

    /** 厂商成员：本厂绑定码列表 */
    @GetMapping("/orgs/{orgId}/vendor/bindings")
    public ApiResponse<Map<String, Object>> bindings(@RequestAttribute("userId") Long userId,
                                                     @PathVariable Long orgId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        orgAccessService.requireMember(userId, orgId);
        return ApiResponse.success(vendorService.listBindings(orgId, page, size));
    }

    /** 厂商成员：本厂 SKU 列表 */
    @GetMapping("/orgs/{orgId}/vendor/skus")
    public ApiResponse<Object> skus(@RequestAttribute("userId") Long userId, @PathVariable Long orgId) {
        orgAccessService.requireMember(userId, orgId);
        return ApiResponse.success(vendorService.listSkus(orgId));
    }

    /** 厂商成员：内容授权列表 */
    @GetMapping("/orgs/{orgId}/vendor/licenses")
    public ApiResponse<Object> licenses(@RequestAttribute("userId") Long userId, @PathVariable Long orgId) {
        orgAccessService.requireMember(userId, orgId);
        return ApiResponse.success(vendorService.listLicenses(orgId));
    }

    /** 平台/厂商：把 AI 创作授权给 B 端组织（进 B 端内容库） */
    @PostMapping("/orgs/{orgId}/vendor/licenses/ai-creations/{creationId}")
    public ApiResponse<Map<String, Object>> licenseAiCreation(@RequestAttribute("userId") Long userId,
                                                              @PathVariable Long orgId,
                                                              @PathVariable Long creationId) {
        orgAccessService.requireMember(userId, orgId);
        return ApiResponse.success(vendorService.licenseAiCreation(orgId, creationId));
    }

    /** B 端内容库：本组织已授权的 AI 创作列表 */
    @GetMapping("/orgs/{orgId}/vendor/licenses/ai-creations")
    public ApiResponse<List<Map<String, Object>>> licensedAiCreations(@RequestAttribute("userId") Long userId,
                                                                      @PathVariable Long orgId) {
        orgAccessService.requireMember(userId, orgId);
        return ApiResponse.success(vendorService.listLicensedAiCreations(orgId));
    }

    /** 撤销 AI 创作授权 */
    @DeleteMapping("/orgs/{orgId}/vendor/licenses/ai-creations/{creationId}")
    public ApiResponse<Boolean> revokeAiCreationLicense(@RequestAttribute("userId") Long userId,
                                                        @PathVariable Long orgId,
                                                        @PathVariable Long creationId) {
        orgAccessService.requireMember(userId, orgId);
        vendorService.revokeAiCreationLicense(orgId, creationId);
        return ApiResponse.success(true);
    }
}
