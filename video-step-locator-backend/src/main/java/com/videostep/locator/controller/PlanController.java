package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 做饭计划（Keep 式训练计划）。登录用户专属。
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
@Slf4j
public class PlanController {

    private final PlanService planService;

    /** 创建每周计划：{title, items:[{dayOffset, videoId?, recipeTitle, mealType}]} */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String title = body.get("title") instanceof String s ? s : null;
            List<Map<String, Object>> items = new ArrayList<>();
            if (body.get("items") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> mm) {
                        Map<String, Object> map = new HashMap<>();
                        mm.forEach((k, v) -> map.put(String.valueOf(k), v));
                        items.add(map);
                    }
                }
            }
            return ApiResponse.success(planService.create(userId, title, items));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(planService.list(userId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(
            @RequestAttribute("userId") Long userId, @PathVariable Long id) {
        try {
            planService.delete(userId, id);
            return ApiResponse.success(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 标记条目完成/取消：{done: true|false} */
    @PostMapping("/items/{itemId}/done")
    public ApiResponse<Map<String, Object>> done(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long itemId,
            @RequestBody(required = false) Map<String, Object> body) {
        boolean done = body != null && Boolean.TRUE.equals(body.get("done"));
        try {
            return ApiResponse.success(planService.markDone(userId, itemId, done));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 删除单条计划条目（v0.5 反馈增强）；删空则连带删除计划（v0.8 反馈） */
    @DeleteMapping("/items/{itemId}")
    public ApiResponse<Map<String, Object>> deleteItem(
            @RequestAttribute("userId") Long userId, @PathVariable Long itemId) {
        try {
            return ApiResponse.success(planService.deleteItem(userId, itemId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 调整条目餐次/星期（v0.5 反馈增强）：body {dayOffset?, mealType?} */
    @PutMapping("/items/{itemId}")
    public ApiResponse<Map<String, Object>> updateItem(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long itemId,
            @RequestBody(required = false) Map<String, Object> body) {
        Integer dayOffset = body != null && body.get("dayOffset") instanceof Number n
                ? n.intValue() : null;
        String mealType = body != null && body.get("mealType") instanceof String s
                ? s : null;
        try {
            return ApiResponse.success(planService.updateItem(userId, itemId, dayOffset, mealType));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
