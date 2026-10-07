package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.UserPreference;
import com.videostep.locator.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
@Slf4j
public class PreferenceController {

    private final PreferenceService preferenceService;

    @GetMapping
    public ApiResponse<UserPreference> get(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(preferenceService.get(userId));
    }

    @PutMapping
    public ApiResponse<UserPreference> update(
            @RequestAttribute("userId") Long userId,
            @RequestBody UserPreference body) {
        return ApiResponse.success(preferenceService.update(userId, body));
    }
}
