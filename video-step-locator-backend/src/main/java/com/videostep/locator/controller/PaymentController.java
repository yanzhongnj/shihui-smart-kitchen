package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * B3 支付接口（模拟支付）：建单 / 支付 / 支付单列表。
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    /** 为待支付账单创建支付单 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestAttribute("userId") Long userId,
                                                   @RequestBody Map<String, Object> body) {
        Long invoiceId = body.get("invoiceId") == null ? null : Long.valueOf(String.valueOf(body.get("invoiceId")));
        return ApiResponse.success(paymentService.createPayment(userId, invoiceId));
    }

    /** 模拟支付成功 */
    @PostMapping("/{id}/pay")
    public ApiResponse<Map<String, Object>> pay(@RequestAttribute("userId") Long userId,
                                                @PathVariable Long id) {
        return ApiResponse.success(paymentService.mockPay(userId, id));
    }

    /** 企业支付单列表 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestAttribute("userId") Long userId,
                                                 @RequestParam Long orgId,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(paymentService.listPayments(userId, orgId, page, size));
    }
}
