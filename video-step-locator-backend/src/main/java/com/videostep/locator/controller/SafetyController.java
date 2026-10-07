package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.safety.RuleExtractService;
import com.videostep.locator.service.safety.SafetyCheckService;
import com.videostep.locator.service.safety.SafetyFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 食物安全规则接口（P0）。设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§7/§14.3。
 * 鉴权：/api/** 默认 authenticated；/rules 普通用户仅 GREEN/MYTH（科普），ADMIN 全量。
 */
@RestController
@RequestMapping("/api/safety")
@RequiredArgsConstructor
@Slf4j
public class SafetyController {

    private final SafetyCheckService safetyCheckService;
    private final RuleExtractService ruleExtractService;
    private final SafetyFeedbackService safetyFeedbackService;

    /** 规则库查询（管理/展示）：category=MED|PROC|IND|ABS|MYTH，level=RED|YELLOW|GREEN */
    @GetMapping("/rules")
    public ApiResponse<List<Map<String, Object>>> rules(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String level) {
        return ApiResponse.success(safetyCheckService.listRules(category, level, isAdmin()));
    }

    /**
     * 安全校验：body { ingredients:[], combinations:[[a,b]], userConditions:[] } → { riskItems:[], blocked }
     */
    @PostMapping("/check")
    public ApiResponse<Map<String, Object>> check(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        List<String> ingredients = strList(b.get("ingredients"));
        List<List<String>> combinations = comboList(b.get("combinations"));
        List<String> userConditions = strList(b.get("userConditions"));
        return ApiResponse.success(safetyCheckService.check(ingredients, combinations, userConditions));
    }

    /** 辟谣列表（MYTH，GREEN 科普："菠菜豆腐放心吃"等） */
    @GetMapping("/myths")
    public ApiResponse<List<Map<String, Object>>> myths() {
        return ApiResponse.success(safetyCheckService.listMyths());
    }

    /** 规则采集（运营增强 D5）：原文/URL → LLM 提取候选 → 待审核队列 */
    @PostMapping("/rules/extract")
    public ApiResponse<Map<String, Object>> extract(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String text = b.get("text") instanceof String s ? s : null;
        String url = b.get("url") instanceof String s ? s : null;
        try {
            if (url != null && !url.isBlank()) {
                return ApiResponse.success(ruleExtractService.extractFromUrl(url));
            }
            if (text == null || text.isBlank()) {
                return ApiResponse.error(400, "请提供原文 text 或 url");
            }
            return ApiResponse.success(ruleExtractService.extractFromText(text));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 规则反馈（反馈闭环）：对某条提醒给 有用/无用/纠错 */
    @PostMapping("/feedback")
    public ApiResponse<Map<String, Object>> feedback(
            @RequestAttribute("userId") Long userId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Long ruleId = b.get("ruleId") instanceof Number n ? n.longValue() : null;
        Integer useful = b.get("useful") instanceof Number n ? n.intValue() : null;
        String comment = b.get("comment") instanceof String s ? s : null;
        try {
            safetyFeedbackService.record(userId, ruleId, useful, comment);
            return ApiResponse.success(Map.of("recorded", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 当前用户是否 ADMIN（决定 rules 是否返回全量） */
    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) return out;
        for (Object e : list) {
            if (e != null) out.add(e.toString());
        }
        return out;
    }

    private List<List<String>> comboList(Object o) {
        List<List<String>> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) return out;
        for (Object e : list) {
            if (e instanceof List<?> inner) {
                List<String> row = new ArrayList<>();
                for (Object x : inner) {
                    if (x != null) row.add(x.toString());
                }
                out.add(row);
            }
        }
        return out;
    }
}
