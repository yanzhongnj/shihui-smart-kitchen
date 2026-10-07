package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.ApiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 开放平台管理（Admin，方向 C）：全部应用列表。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
public class AdminApiClientController {

    private final ApiClientService apiClientService;

    @GetMapping("/api-clients")
    public ApiResponse<Map<String, Object>> clients(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(apiClientService.adminListClients(page, size));
    }
}
