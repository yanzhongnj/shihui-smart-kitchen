package com.videostep.locator.controller;

import com.videostep.locator.dto.request.AiConfigUpdateRequest;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.aiconfig.AiConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 模型配置中心管理接口（仅 ADMIN）。设计文档 v0.3 §6。
 * 与现有管理端一致：/api/admin/** + 类级 @PreAuthorize("hasRole('ADMIN')") + /api/** JWT。
 */
@RestController
@RequestMapping("/api/admin/ai-config")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiConfigController {

    private final AiConfigService configService;

    /** 引擎配置列表（按类型分组，key 脱敏） */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.success(configService.list());
    }

    /** 单引擎配置详情（脱敏） */
    @GetMapping("/{providerKey}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String providerKey) {
        return ApiResponse.success(configService.detail(providerKey));
    }

    /** 更新配置（热生效；apiKey 留空=不变） */
    @PutMapping("/{providerKey}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String providerKey,
                                                   @RequestBody AiConfigUpdateRequest req,
                                                   @RequestAttribute("userId") Long userId) {
        return ApiResponse.success("配置已更新并热生效", configService.update(providerKey, req, userId));
    }

    /** 连通性测试（视频/文生图仅校验鉴权，不真提交） */
    @PostMapping("/{providerKey}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable String providerKey,
                                                 @RequestAttribute("userId") Long userId) {
        return ApiResponse.success(configService.test(providerKey, userId));
    }

    /** 恢复环境变量默认 */
    @PostMapping("/{providerKey}/restore-default")
    public ApiResponse<Map<String, Object>> restoreDefault(@PathVariable String providerKey,
                                                           @RequestAttribute("userId") Long userId) {
        return ApiResponse.success("已恢复默认（环境变量）", configService.restoreDefault(providerKey, userId));
    }

    /** 启用 */
    @PostMapping("/{providerKey}/enable")
    public ApiResponse<Map<String, Object>> enable(@PathVariable String providerKey,
                                                   @RequestAttribute("userId") Long userId) {
        return ApiResponse.success("已启用", configService.setEnabled(providerKey, true, userId));
    }

    /** 停用 */
    @PostMapping("/{providerKey}/disable")
    public ApiResponse<Map<String, Object>> disable(@PathVariable String providerKey,
                                                    @RequestAttribute("userId") Long userId) {
        return ApiResponse.success("已停用", configService.setEnabled(providerKey, false, userId));
    }

    /** 全部引擎健康快照 */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(configService.healthAll());
    }

    /** 变更日志（审计） */
    @GetMapping("/change-logs")
    public ApiResponse<List<Map<String, Object>>> changeLogs(
            @RequestParam(required = false) String providerKey) {
        return ApiResponse.success(configService.changeLogs(providerKey));
    }
}
