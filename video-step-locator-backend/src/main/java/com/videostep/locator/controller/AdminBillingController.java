package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.BillingService;
import com.videostep.locator.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * M3：计费与用量（仅 ADMIN）。统一 /api/admin/billing/**
 *
 * <p>按 actionType 分项统计（videogen=生成秒数、tts=字符数、其余=字节），
 * 为待办 #15/#16 提供分项视图；纯新增端点，不改动现有 BillingService/计费逻辑。</p>
 */
@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminBillingController {

    private final BillingService billingService;
    private final UserService userService;

    /** 全局用量分项（按 actionType：条数 + 数量 + 语义说明）+ 计费公式 */
    @GetMapping("/global")
    public ApiResponse<Map<String, Object>> global() {
        return ApiResponse.success(buildReport(null));
    }

    /** 单用户用量分项 */
    @GetMapping("/users/{id}")
    public ApiResponse<Map<String, Object>> user(@PathVariable Long id) {
        if (userService.getById(id) == null) return ApiResponse.error(404, "用户不存在");
        Map<String, Object> r = buildReport(id);
        r.put("userId", id);
        r.put("username", username(id));
        return ApiResponse.success(r);
    }

    private Map<String, Object> buildReport(Long userId) {
        Map<String, Object> m = new HashMap<>(billingService.summaryByType(userId));
        m.put("semantics", Map.of(
                "parse", "字节（解析流量）",
                "preview", "字节（预览流量）",
                "download", "字节（下载流量）",
                "process", "字节（处理流量）",
                "videogen", "秒（AI 视频生成时长）",
                "tts", "字符（语音讲解合成）"));
        return m;
    }

    private String username(Long userId) {
        if (userId == null) return null;
        try { return userService.getById(userId).getUsername(); }
        catch (Exception e) { return null; }
    }
}
