package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.ApiClientService;
import com.videostep.locator.service.OrgAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 开放平台开发者接口（方向 C）：创建应用（生成 Key/Secret）/ 我的应用 / 撤销。
 */
@RestController
@RequestMapping("/api/open-platform")
@RequiredArgsConstructor
@Slf4j
public class ApiClientController {

    private final ApiClientService apiClientService;
    private final OrgAccessService orgAccessService;

    /** 创建应用（企业成员 → 归企业；个人 → 归个人） */
    @PostMapping("/clients")
    public ApiResponse<Map<String, Object>> create(@RequestAttribute("userId") Long userId,
                                                   @RequestBody Map<String, Object> body) {
        Long orgId = orgAccessService.getOrgIdOf(userId);
        @SuppressWarnings("unchecked")
        List<String> scopes = body.get("scopes") instanceof List ? (List<String>) body.get("scopes") : List.of("recipe");
        return ApiResponse.success(apiClientService.createClient(userId, orgId, (String) body.get("name"), scopes));
    }

    /** 我的应用 */
    @GetMapping("/clients")
    public ApiResponse<Map<String, Object>> mine(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(apiClientService.listMyClients(userId, orgAccessService.getOrgIdOf(userId)));
    }

    /** 撤销应用 */
    @DeleteMapping("/clients/{id}")
    public ApiResponse<Map<String, Object>> revoke(@RequestAttribute("userId") Long userId,
                                                   @PathVariable Long id) {
        apiClientService.revoke(userId, id);
        return ApiResponse.success(Map.of("message", "应用已撤销"));
    }
}
