package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.*;
import com.videostep.locator.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B3 支付网关（当前 channel=mock 模拟支付）。
 * 流程：企业 owner 对「待支付」账单建单 → 模拟支付成功 → 账单 paid → 若企业欠费则自动恢复并顺延订阅。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentOrderRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrgRepository orgRepository;
    private final OrgSubscriptionRepository subscriptionRepository;
    private final OrgAccessService orgAccessService;

    /** 为待支付账单创建支付单（同一账单未完成的支付单复用） */
    @Transactional
    public Map<String, Object> createPayment(Long userId, Long invoiceId) {
        Invoice inv = invoiceRepository.selectById(invoiceId);
        if (inv == null) throw new IllegalArgumentException("账单不存在");
        orgAccessService.requireMember(userId, inv.getOrgId());
        if (!"pending".equals(inv.getStatus())) {
            throw new IllegalArgumentException("该账单无需支付（状态：" + inv.getStatus() + "）");
        }
        PaymentOrder existing = paymentRepository.findCreatedByInvoice(invoiceId);
        if (existing != null) return toMap(existing);
        PaymentOrder order = PaymentOrder.builder()
                .invoiceId(invoiceId)
                .orgId(inv.getOrgId())
                .userId(userId)
                .amount(inv.getAmount())
                .channel("mock")
                .status(PaymentOrder.STATUS_CREATED)
                .build();
        paymentRepository.insert(order);
        log.info("创建支付单 id={} invoice={} amount={}", order.getId(), invoiceId, inv.getAmount());
        return toMap(order);
    }

    /** 模拟支付成功：支付单 success → 账单 paid → 欠费企业恢复 + 顺延订阅 */
    @Transactional
    public Map<String, Object> mockPay(Long userId, Long paymentId) {
        PaymentOrder order = paymentRepository.selectById(paymentId);
        if (order == null) throw new IllegalArgumentException("支付单不存在");
        orgAccessService.requireMember(userId, order.getOrgId());
        if (!PaymentOrder.STATUS_CREATED.equals(order.getStatus())) {
            throw new IllegalArgumentException("支付单状态不可支付：" + order.getStatus());
        }
        order.setStatus(PaymentOrder.STATUS_SUCCESS);
        order.setPaidAt(LocalDateTime.now());
        order.setPayNo("PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        paymentRepository.updateById(order);

        Invoice inv = invoiceRepository.selectById(order.getInvoiceId());
        if (inv != null) {
            inv.setStatus("paid");
            inv.setPaidAt(LocalDateTime.now());
            invoiceRepository.updateById(inv);
        }

        // 欠费恢复 + 顺延订阅
        Org org = orgRepository.selectById(order.getOrgId());
        if (org != null) {
            if (org.getStatus() != null && org.getStatus() == Org.STATUS_OVERDUE) {
                org.setStatus(Org.STATUS_ENABLED);
                log.info("企业 {} 欠费已恢复（支付成功）", org.getId());
            }
            LocalDateTime now = LocalDateTime.now();
            OrgSubscription active = subscriptionRepository.findActiveByOrg(org.getId());
            LocalDateTime newExpired = (active != null && active.getExpiredAt() != null && active.getExpiredAt().isAfter(now))
                    ? active.getExpiredAt().plusMonths(1) : now.plusMonths(1);
            if (active != null) {
                active.setExpiredAt(newExpired);
                subscriptionRepository.updateById(active);
            } else {
                subscriptionRepository.insert(OrgSubscription.builder()
                        .orgId(org.getId())
                        .planCode(org.getPlanType() == null ? "free" : org.getPlanType())
                        .status("active")
                        .startedAt(now)
                        .expiredAt(newExpired)
                        .build());
            }
            org.setExpiredAt(newExpired);
            orgRepository.updateById(org);
        }
        log.info("支付成功 payment={} invoice={} org={}", paymentId, order.getInvoiceId(), order.getOrgId());
        return toMap(order);
    }

    /** 企业支付单列表 */
    public Map<String, Object> listPayments(Long userId, Long orgId, int page, int size) {
        orgAccessService.requireMember(userId, orgId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<PaymentOrder> qw = new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getOrgId, orgId)
                .orderByDesc(PaymentOrder::getId);
        long total = paymentRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PaymentOrder p : paymentRepository.selectList(qw)) {
            items.add(toMap(p));
        }
        return Map.of("items", items, "total", total);
    }

    private Map<String, Object> toMap(PaymentOrder p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("invoiceId", p.getInvoiceId());
        m.put("orgId", p.getOrgId());
        m.put("amount", p.getAmount());
        m.put("channel", p.getChannel());
        m.put("status", p.getStatus());
        m.put("payNo", p.getPayNo());
        m.put("createdAt", p.getCreatedAt());
        m.put("paidAt", p.getPaidAt());
        return m;
    }
}
