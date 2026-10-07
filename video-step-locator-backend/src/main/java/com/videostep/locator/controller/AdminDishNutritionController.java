package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.health.photo.DishNutritionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 菜品级营养库 Admin 管理（P1，Web 管理端「菜品库审核」）。设计文档 v0.2 §8.4。
 * 仅 ROLE_ADMIN；AI 兜底收录的候选（source=ai, is_verified=0）在此审核通过/驳回。
 */
@RestController
@RequestMapping("/api/admin/dish-nutrition")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDishNutritionController {

    private final DishNutritionService dishNutritionService;

    /** 分页列表（keyword/isVerified/source/sortHit） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer isVerified,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "false") boolean sortHit,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.success(dishNutritionService.adminList(keyword, isVerified, source, sortHit, page, size));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 统计 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.success(dishNutritionService.stats());
    }

    /** 编辑（菜名/菜系/份量/营养/食材） */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            String dishName = body.get("dishName") instanceof String s ? s : null;
            String cuisine = body.get("cuisine") instanceof String s ? s : null;
            Integer servingGrams = body.get("servingGrams") instanceof Number n ? n.intValue() : null;
            @SuppressWarnings("unchecked")
            Map<String, Object> nutrition = body.get("nutrition") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            @SuppressWarnings("unchecked")
            List<String> ingredients = body.get("ingredients") instanceof List<?> l
                    ? (List<String>) l : null;
            var d = dishNutritionService.update(id, dishName, cuisine, servingGrams, nutrition, ingredients);
            return ApiResponse.success(Map.of("id", d.getId(), "dishName", d.getDishName()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 审核通过（AI 候选 → is_verified=1） */
    @PostMapping("/{id}/verify")
    public ApiResponse<Map<String, Object>> verify(@PathVariable Long id) {
        try {
            var d = dishNutritionService.verify(id);
            return ApiResponse.success(Map.of("id", d.getId(), "isVerified", d.getIsVerified()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    /** 驳回/删除（未复核候选或冗余条目） */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            dishNutritionService.delete(id);
            return ApiResponse.success(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }
}
