package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.BillingService;
import com.videostep.locator.service.OrgAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final BillingService billingService;
    private final OrgAccessService orgAccessService;

    /** 注册用户用量/费用摘要（公式待定）；B 端企业成员同时返回企业维度用量 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestAttribute("userId") Long userId) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(billingService.summary(userId));
        Long orgId = orgAccessService.getOrgIdOf(userId);
        if (orgId != null) {
            m.put("orgId", orgId);
            m.put("orgBytes", billingService.summaryOrg(orgId));
        }
        return ApiResponse.success(m);
    }
}
