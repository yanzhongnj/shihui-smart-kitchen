package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 月度烹饪报告（Keep 式月度总结）。登录用户专属。
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/monthly")
    public ApiResponse<Map<String, Object>> monthly(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) String month) {
        try {
            return ApiResponse.success(reportService.monthlyReport(userId, month));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
