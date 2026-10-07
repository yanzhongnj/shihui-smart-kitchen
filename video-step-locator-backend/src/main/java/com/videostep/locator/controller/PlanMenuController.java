package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.plan.MenuPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 菜单生成接口（做饭计划执行闭环 P0）。登录用户专属。
 */
@RestController
@RequestMapping("/api/plan/menu")
@RequiredArgsConstructor
@Slf4j
public class PlanMenuController {

    private final MenuPlannerService menuPlannerService;

    /** AI 生成一周菜单（预览）：body {generateMode?, respectTaboos?, respectMedications?, week?, maxCookTime?, cuisines[]?} */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(
            @RequestAttribute("userId") Long userId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.success(menuPlannerService.generate(userId, body == null ? Map.of() : body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 确认菜单 → 写入 cooking_plans + items；body 可选 {selectedRecipeIds: [recipeId...]}（仅写勾选项） */
    @PostMapping("/{menuId}/confirm")
    public ApiResponse<Map<String, Object>> confirm(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long menuId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            List<Long> selected = null;
            if (body != null && body.get("selectedRecipeIds") instanceof List<?> list) {
                selected = new java.util.ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Number n) selected.add(n.longValue());
                }
            }
            return ApiResponse.success(menuPlannerService.confirm(userId, menuId, selected));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 放弃预览 */
    @PostMapping("/{menuId}/discard")
    public ApiResponse<Map<String, Object>> discard(
            @RequestAttribute("userId") Long userId, @PathVariable Long menuId) {
        try {
            return ApiResponse.success(menuPlannerService.discard(userId, menuId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 查询某周菜单：?week=2026-08-31 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) String week) {
        return ApiResponse.success(menuPlannerService.list(userId, week));
    }
}
