package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.AppVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
@Slf4j
public class AppVersionController {

    private final AppVersionService appVersionService;

    @GetMapping("/version")
    public ApiResponse<Map<String, Object>> version(
            @RequestParam String platform,
            @RequestParam(required = false, defaultValue = "0") String current) {
        return ApiResponse.success(appVersionService.check(platform, current));
    }
}
