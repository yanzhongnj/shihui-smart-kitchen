package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.CheckinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 炒菜打卡（Keep 式习惯记录）。登录用户专属（/api/** 默认 authenticated）。
 */
@RestController
@RequestMapping("/api/checkins")
@RequiredArgsConstructor
@Slf4j
public class CheckinController {

    private final CheckinService checkinService;

    @PostMapping
    public ApiResponse<Map<String, Object>> add(
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            Long videoId = body.get("videoId") instanceof Number n ? n.longValue() : null;
            String recipeTitle = body.get("recipeTitle") instanceof String s ? s : null;
            String mealType = body.get("mealType") instanceof String s ? s : null;
            String mood = body.get("mood") instanceof String s ? s : null;
            String note = body.get("note") instanceof String s ? s : null;
            Integer calories = body.get("calories") instanceof Number n ? n.intValue() : null;
            Double portionFactor = body.get("portionFactor") instanceof Number n ? n.doubleValue() : null;
            return ApiResponse.success(checkinService.add(userId, videoId, recipeTitle, mealType, mood, note, calories, portionFactor));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        try {
            checkinService.delete(userId, id);
            return ApiResponse.success(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) String month) {
        return ApiResponse.success(checkinService.listByMonth(userId, month));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(checkinService.stats(userId));
    }
}
