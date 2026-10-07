package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.GrowthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 厨艺成长：等级 EXP / 成就徽章。登录用户专属。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class GrowthController {

    private final GrowthService growthService;

    @GetMapping("/level")
    public ApiResponse<Map<String, Object>> level(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(growthService.getLevelInfo(userId));
    }

    @GetMapping("/badges")
    public ApiResponse<List<Map<String, Object>>> badges(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(growthService.getBadges(userId));
    }
}
