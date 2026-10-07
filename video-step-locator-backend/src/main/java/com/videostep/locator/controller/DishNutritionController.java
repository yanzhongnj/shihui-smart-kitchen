package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.DishNutrition;
import com.videostep.locator.service.health.AiNutritionService;
import com.videostep.locator.service.health.photo.DishNutritionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 菜品级营养库（用户端，P1）。设计文档 v0.2 §7.1。
 * 搜索：拍照确认页手动选择辅助；自定义：用户录入自己的常做菜（source=user，is_verified=1）。
 * normalize：APP 直接编辑菜名时归一到规范名并返回库匹配；estimate：自定义菜名 AI 估算（保存我的常做菜用）。
 */
@RestController
@RequestMapping("/api/health/dish-nutrition")
@RequiredArgsConstructor
public class DishNutritionController {

    private final DishNutritionService dishNutritionService;
    private final AiNutritionService aiNutritionService;

    /** 菜品库搜索（拍照确认页/手动改选辅助） */
    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(dishNutritionService.search(keyword, limit));
    }

    /** 菜名归一 + 库匹配（APP 直接编辑菜名辅助）：{input, name, matched} */
    @GetMapping("/normalize")
    public ApiResponse<Map<String, Object>> normalize(@RequestParam(required = false) String name) {
        return ApiResponse.success(dishNutritionService.normalizeDishName(name));
    }

    /** 自定义菜名 AI 营养估算（保存「我的常做菜」前预览/落库用，仅显式触发时调用） */
    @PostMapping("/estimate")
    public ApiResponse<Map<String, Object>> estimate(@RequestBody Map<String, Object> body) {
        String name = body.get("name") instanceof String s ? s.trim() : "";
        if (name.isBlank()) {
            return ApiResponse.error(400, "请输入菜名");
        }
        return ApiResponse.success(aiNutritionService.estimateByDescription(name));
    }

    /** 用户自定义菜品营养（source=user，is_verified=1） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String dishName = body.get("dishName") instanceof String s ? s : null;
            String cuisine = body.get("cuisine") instanceof String s ? s : null;
            int servingGrams = body.get("servingGrams") instanceof Number n ? n.intValue() : 300;
            @SuppressWarnings("unchecked")
            Map<String, Object> nutrition = body.get("nutrition") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            @SuppressWarnings("unchecked")
            List<String> ingredients = body.get("ingredients") instanceof List<?> l
                    ? (List<String>) l : null;
            DishNutrition d = dishNutritionService.createUserDish(dishName, cuisine, servingGrams, nutrition, ingredients);
            return ApiResponse.success(Map.of("id", d.getId(), "dishName", d.getDishName()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
