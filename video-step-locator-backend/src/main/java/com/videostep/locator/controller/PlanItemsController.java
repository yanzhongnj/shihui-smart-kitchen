package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.plan.KitchenSchedulerService;
import com.videostep.locator.service.plan.PlanExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 计划条目执行接口（做饭计划执行闭环 P0 线路 B + P1 厨电调度）。登录用户专属。
 */
@RestController
@RequestMapping("/api/plan/items")
@RequiredArgsConstructor
@Slf4j
public class PlanItemsController {

    private final PlanExecutionService planExecutionService;
    private final KitchenSchedulerService kitchenSchedulerService;

    /** 完成前调整份量系数：body {scale: 0.5|1.0|1.5} */
    @PostMapping("/{itemId}/portion")
    public ApiResponse<Map<String, Object>> portion(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long itemId,
            @RequestBody Map<String, Object> body) {
        double scale = body.get("scale") instanceof Number n ? n.doubleValue() : 1.0;
        try {
            return ApiResponse.success(planExecutionService.setPortion(userId, itemId, scale));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 线路 B：条目「跟做」视频带出（有 videoId 直进；无则后端搜索首个候选视频；失败由 APP 跳寻味） */
    @PostMapping("/{itemId}/attach-video")
    public ApiResponse<Map<String, Object>> attachVideo(
            @RequestAttribute("userId") Long userId, @PathVariable Long itemId) {
        try {
            return ApiResponse.success(planExecutionService.attachVideo(userId, itemId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    // ================= P1 厨电调度（线路 A） =================

    /** 编排并启动厨电时间轴：body {startAt?: "HH:mm"}（可选，默认按餐次推断） */
    @PostMapping("/{itemId}/schedule")
    public ApiResponse<Map<String, Object>> schedule(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long itemId,
            @RequestBody(required = false) Map<String, Object> body) {
        String startAt = body != null && body.get("startAt") instanceof String s ? s : null;
        try {
            return ApiResponse.success(kitchenSchedulerService.scheduleItem(userId, itemId, startAt));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 取消厨电调度 */
    @PostMapping("/{itemId}/schedule/cancel")
    public ApiResponse<Map<String, Object>> cancelSchedule(
            @RequestAttribute("userId") Long userId, @PathVariable Long itemId) {
        try {
            return ApiResponse.success(kitchenSchedulerService.cancelSchedule(userId, itemId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 查看厨电调度时间轴与状态 */
    @GetMapping("/{itemId}/schedule")
    public ApiResponse<Map<String, Object>> getSchedule(
            @RequestAttribute("userId") Long userId, @PathVariable Long itemId) {
        try {
            return ApiResponse.success(kitchenSchedulerService.getSchedule(userId, itemId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
