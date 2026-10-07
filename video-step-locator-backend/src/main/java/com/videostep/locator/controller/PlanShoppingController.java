package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.plan.ShoppingListService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 购物清单接口（做饭计划执行闭环 P0）。登录用户专属。
 */
@RestController
@RequestMapping("/api/plan/shopping-list")
@RequiredArgsConstructor
@Slf4j
public class PlanShoppingController {

    private final ShoppingListService shoppingListService;

    /** 从计划生成购物清单：body {planId, persons?, stockIngredients[]?}，返回 skippedItems（无 recipe_id 手工条目，仅提示不阻塞） */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(shoppingListService.generate(userId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 清单详情（含导出文本） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @RequestAttribute("userId") Long userId, @PathVariable Long id) {
        try {
            return ApiResponse.success(shoppingListService.detail(userId, id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 手动勾选/改量/补录：body items[]（整体替换） */
    @PutMapping("/{id}/items")
    public ApiResponse<Map<String, Object>> updateItems(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @RequestBody(required = false) List<Map<String, Object>> items) {
        try {
            return ApiResponse.success(shoppingListService.updateItems(userId, id, items == null ? List.of() : items));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 生成导出文本（复制/分享） */
    @PostMapping("/{id}/export")
    public ApiResponse<Map<String, Object>> export(
            @RequestAttribute("userId") Long userId, @PathVariable Long id) {
        try {
            return ApiResponse.success(shoppingListService.export(userId, id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
