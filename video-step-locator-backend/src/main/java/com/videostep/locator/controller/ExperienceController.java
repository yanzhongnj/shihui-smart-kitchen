package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.ExperienceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 游客体验视频：无需登录即可查看公开体验视频（含操作步骤）。
 */
@RestController
@RequestMapping("/api/experience")
@RequiredArgsConstructor
@Slf4j
public class ExperienceController {

    private final ExperienceService experienceService;

    @GetMapping("/videos")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(experienceService.list());
    }

    @GetMapping("/videos/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(experienceService.detail(id));
    }
}
