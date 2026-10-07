package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.ShareLink;
import com.videostep.locator.service.ShareLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ShareLinkController {

    private final ShareLinkService shareLinkService;

    /** 生成站外分享链接（视频所有者） */
    @PostMapping("/api/share/link/{videoId}")
    public ApiResponse<Map<String, Object>> generate(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long videoId,
            @RequestBody(required = false) Map<String, Object> body) {
        Integer expireDays = body != null && body.get("expireDays") instanceof Number n ? n.intValue() : null;
        ShareLink link = shareLinkService.generate(userId, videoId, expireDays);
        // 注意：Map.of 遇 null 抛 NPE，expiresAt 可能为 null → 用 HashMap
        java.util.HashMap<String, Object> data = new java.util.HashMap<>();
        data.put("code", link.getCode());
        data.put("expiresAt", link.getExpiresAt());
        data.put("shareUrl", "/s/" + link.getCode());
        return ApiResponse.success(data);
    }

    /** 我的分享链接列表 */
    @GetMapping("/api/share/links")
    public ApiResponse<List<ShareLink>> list(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(shareLinkService.list(userId));
    }

    /** 撤销分享链接 */
    @DeleteMapping("/api/share/link/{code}")
    public ApiResponse<Map<String, Object>> revoke(
            @RequestAttribute("userId") Long userId,
            @PathVariable String code) {
        shareLinkService.revoke(userId, code);
        return ApiResponse.success(Map.of("message", "已撤销"));
    }

    /** 游客只读访问：返回步骤 + 菜谱（无鉴权，直接访问后端 /s/{code} 亦可） */
    @GetMapping("/s/{code}")
    public ApiResponse<Map<String, Object>> resolve(@PathVariable String code) {
        return resolveInternal(code);
    }

    /** 游客只读访问（API 形式，走 /api 代理，供 APP/Web 使用） */
    @GetMapping("/api/share/{code}")
    public ApiResponse<Map<String, Object>> resolveApi(@PathVariable String code) {
        return resolveInternal(code);
    }

    private ApiResponse<Map<String, Object>> resolveInternal(String code) {
        try {
            return ApiResponse.success(shareLinkService.resolve(code));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }
}
