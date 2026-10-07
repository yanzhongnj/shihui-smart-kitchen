package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.*;
import com.videostep.locator.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * B3 计费闭环定时任务：
 * 1. 每月生成账单（invoice-cron，默认每月 1 日 02:00）——为当月到期的 active 订阅生成月费账单（幂等查重）；
 * 2. 每日冻结欠费企业（freeze-cron）——订阅到期后 orgs.status → 2（欠费停服）；
 * 3. 每日配额告警（alert-cron）——配额使用 ≥80% 输出告警日志。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingJob {

    private final OrgRepository orgRepository;
    private final OrgSubscriptionRepository subscriptionRepository;
    private final OrgPlanRepository orgPlanRepository;
    private final InvoiceRepository invoiceRepository;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.billing.alert-threshold:80}")
    private int alertThreshold;

    /** 每月生成账单：对「本月到期」的 active 订阅生成账单（幂等） */
    @Scheduled(cron = "${app.billing.invoice-cron:0 0 2 1 * *}")
    public void generateMonthlyInvoices() {
        int created = 0;
        try {
            LocalDateTime now = LocalDateTime.now();
            List<OrgSubscription> subs = subscriptionRepository.selectList(
                    new LambdaQueryWrapper<OrgSubscription>()
                            .eq(OrgSubscription::getStatus, "active"));
            for (OrgSubscription sub : subs) {
                if (sub.getExpiredAt() == null) continue;
                // 只处理在本月到期或已到期的订阅（补账/续费账单统一由 subscribe 生成，这里兜底补缺）
                if (sub.getExpiredAt().getYear() != now.getYear()
                        || sub.getExpiredAt().getMonthValue() != now.getMonthValue()) continue;
                OrgPlan plan = orgPlanRepository.findEnabledByCode(sub.getPlanCode());
                if (plan == null || plan.getPriceMonthly().signum() <= 0) continue;
                String desc = plan.getName() + " 月费（" + now.format(DateTimeFormatter.ofPattern("yyyy-MM")) + "）";
                if (invoiceRepository.countByOrgAndDescription(sub.getOrgId(), desc) > 0) continue;
                Invoice inv = Invoice.builder()
                        .orgId(sub.getOrgId())
                        .amount(plan.getPriceMonthly())
                        .currency("CNY")
                        .status("pending")
                        .description(desc)
                        .build();
                invoiceRepository.insert(inv);
                created++;
            }
            log.info("[B端计费] 月度账单生成完成 created={}", created);
        } catch (Exception e) {
            log.warn("[B端计费] 月度账单生成异常: {}", e.getMessage());
        }
    }

    /** 每日冻结欠费企业：订阅到期 → orgs.status=2（欠费停服） */
    @Scheduled(cron = "${app.billing.freeze-cron:0 0 1 * * *}")
    public void freezeOverdueOrgs() {
        int frozen = 0;
        try {
            LocalDateTime now = LocalDateTime.now();
            List<Org> orgs = orgRepository.selectList(
                    new LambdaQueryWrapper<Org>()
                            .eq(Org::getStatus, Org.STATUS_ENABLED)
                            .isNotNull(Org::getExpiredAt)
                            .lt(Org::getExpiredAt, now));
            for (Org org : orgs) {
                // 仅当无有效订阅时才冻结（subscribe 会续期 expired_at）
                OrgSubscription active = subscriptionRepository.findActiveByOrg(org.getId());
                if (active != null && active.getExpiredAt() != null
                        && active.getExpiredAt().isAfter(now)) continue;
                org.setStatus(Org.STATUS_OVERDUE);
                orgRepository.updateById(org);
                frozen++;
                log.info("[B端计费] 企业 {}（{}）订阅到期，已冻结为欠费停服", org.getId(), org.getName());
            }
            if (frozen > 0) log.info("[B端计费] 欠费冻结完成 frozen={}", frozen);
        } catch (Exception e) {
            log.warn("[B端计费] 欠费冻结异常: {}", e.getMessage());
        }
    }

    /** 每日配额告警：配额使用 ≥80% 输出告警日志（前端配额进度条同时展示） */
    @SuppressWarnings("unchecked")
    @Scheduled(cron = "${app.billing.alert-cron:0 0 0 * * *}")
    public void quotaAlert() {
        int warned = 0;
        try {
            List<Org> orgs = orgRepository.selectList(
                    new LambdaQueryWrapper<Org>().eq(Org::getStatus, Org.STATUS_ENABLED));
            for (Org org : orgs) {
                if (org.getQuota() == null || org.getQuota().isBlank()) continue;
                Map<String, Object> q = objectMapper.readValue(org.getQuota(), Map.class);
                long videoLimit = num(q.get("video_limit"), 50);
                long memberLimit = num(q.get("member_limit"), 5);
                if (videoLimit <= 0 && memberLimit <= 0) continue;
                // 视频用量（真实统计）
                long videos = videoRepository.countByOrg(org.getId());
                if (videoLimit > 0 && videos * 100 / videoLimit >= alertThreshold) {
                    warned++;
                    log.warn("[B端计费] 配额告警：企业 {}（{}）视频配额使用率 ≥{}%（{}/{}）",
                            org.getId(), org.getName(), alertThreshold, videos, videoLimit);
                }
            }
            if (warned > 0) log.info("[B端计费] 配额告警完成 warned={}", warned);
        } catch (Exception e) {
            log.warn("[B端计费] 配额告警异常: {}", e.getMessage());
        }
    }

    private long num(Object o, long def) {
        if (o == null) return def;
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return def; }
    }
}