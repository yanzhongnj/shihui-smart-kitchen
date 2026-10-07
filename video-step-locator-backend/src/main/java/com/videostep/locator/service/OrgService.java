package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.*;
import com.videostep.locator.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * B 端企业服务：创建企业、成员管理、企业统计、计费摘要、订阅。
 * 鉴权统一走 OrgAccessService（手工 org_id 过滤，不引 MP 多租户插件）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgService {

    private final OrgRepository orgRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrgPlanRepository orgPlanRepository;
    private final OrgSubscriptionRepository orgSubscriptionRepository;
    private final InvoiceRepository invoiceRepository;
    private final UserService userService;
    private final UserRepository userRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final VideoRepository videoRepository;
    private final ProcessingTaskRepository taskRepository;
    private final OrgAccessService orgAccessService;
    private final QuotaService quotaService;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_FREE_QUOTA =
            "{\"video_limit\":50,\"task_limit\":100,\"storage_bytes\":5368709120,\"member_limit\":5,\"download_limit\":50}";

    // ---------------- 企业基础 ----------------

    @Transactional
    public Org createOrg(Long userId, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("企业名称不能为空");
        if (orgAccessService.getOrgIdOf(userId) != null) {
            throw new IllegalArgumentException("您已加入企业，不能重复创建/加入");
        }
        String code = "org_" + Long.toHexString(System.currentTimeMillis())
                + UUID.randomUUID().toString().substring(0, 4);
        Org org = Org.builder()
                .name(name.trim())
                .code(code)
                .ownerId(userId)
                .planType(Org.PLAN_FREE)
                .status(Org.STATUS_ENABLED)
                .quota(DEFAULT_FREE_QUOTA)
                .build();
        orgRepository.insert(org);

        OrgMember owner = OrgMember.builder()
                .orgId(org.getId())
                .userId(userId)
                .role(OrgMember.ROLE_OWNER)
                .status(1)
                .joinedAt(LocalDateTime.now())
                .build();
        orgMemberRepository.insert(owner);
        log.info("org created id={} code={} owner={}", org.getId(), org.getCode(), userId);
        return org;
    }

    /** 我的企业信息（含我的角色 + 成员数 + 用量摘要；无企业返回 null） */
    public Map<String, Object> getMyOrg(Long userId) {
        Org org = orgAccessService.getOrgOf(userId);
        if (org == null) return null;
        Map<String, Object> m = toOrgMap(org);
        m.put("myRole", orgAccessService.getUserRole(userId, org.getId()));
        m.put("memberCount", orgMemberRepository.selectCount(
                new LambdaQueryWrapper<OrgMember>().eq(OrgMember::getOrgId, org.getId()).eq(OrgMember::getStatus, 1)));
        m.put("usage", billingSummary(org.getId()));
        return m;
    }

    @Transactional
    public Org updateOrg(Long userId, Long orgId, String name, String logo) {
        orgAccessService.requireMember(userId, orgId);
        orgAccessService.requireRole(userId, orgId, OrgMember.ROLE_ADMIN);
        Org org = requireOrg(orgId);
        if (name != null && !name.isBlank()) org.setName(name.trim());
        if (logo != null) org.setLogo(logo.isBlank() ? null : logo);
        orgRepository.updateById(org);
        return org;
    }

    private Org requireOrg(Long orgId) {
        Org org = orgRepository.selectById(orgId);
        if (org == null) throw new IllegalArgumentException("企业不存在");
        return org;
    }

    // ---------------- 成员管理 ----------------

    public Map<String, Object> listMembers(Long userId, Long orgId, int page, int size) {
        orgAccessService.requireMember(userId, orgId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<OrgMember> qw = new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .eq(OrgMember::getStatus, 1)
                .orderByAsc(OrgMember::getRole);
        long total = orgMemberRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = orgMemberRepository.selectList(qw).stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.getId());
            map.put("userId", m.getUserId());
            map.put("role", m.getRole());
            map.put("joinedAt", m.getJoinedAt());
            User u = userService.getById(m.getUserId());
            map.put("username", u == null ? null : u.getUsername());
            map.put("nickname", u == null ? null : u.getNickname());
            return map;
        }).collect(Collectors.toList());
        return Map.of("items", items, "total", total);
    }

    @Transactional
    public Map<String, Object> addMember(Long userId, Long orgId, String account, String role) {
        orgAccessService.requireRole(userId, orgId, OrgMember.ROLE_ADMIN);
        Org org = requireOrg(orgId);
        if (account == null || account.isBlank()) throw new IllegalArgumentException("请输入成员用户名或邮箱");
        User target = userRepository.findByUsername(account.trim()).orElse(null);
        if (target == null) target = userRepository.findByEmail(account.trim()).orElse(null);
        if (target == null) throw new IllegalArgumentException("用户不存在：" + account);
        if (target.getId().equals(org.getOwnerId())) throw new IllegalArgumentException("该用户已是企业主");
        if (orgAccessService.isMember(target.getId(), orgId)) throw new IllegalArgumentException("该用户已是企业成员");

        // 一个用户只属于一个组织：若已加入其它组织，先移除旧成员关系（保持单 org 约束）
        Org other = orgAccessService.getOrgOf(target.getId());
        if (other != null && !other.getId().equals(orgId)) {
            throw new IllegalArgumentException("该用户已属于其它企业（" + other.getName() + "），请先让其退出");
        }

        quotaService.checkMemberQuota(orgId);
        String finalRole = (role == null || role.isBlank()) ? OrgMember.ROLE_MEMBER : role;
        OrgMember member = OrgMember.builder()
                .orgId(orgId)
                .userId(target.getId())
                .role(finalRole)
                .status(1)
                .joinedAt(LocalDateTime.now())
                .build();
        orgMemberRepository.insert(member);
        log.info("org member added org={} user={} role={}", orgId, target.getId(), finalRole);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", member.getId());
        m.put("userId", target.getId());
        m.put("username", target.getUsername());
        m.put("nickname", target.getNickname());
        m.put("role", finalRole);
        return m;
    }

    @Transactional
    public OrgMember updateMemberRole(Long userId, Long orgId, Long memberId, String role) {
        orgAccessService.requireRole(userId, orgId, OrgMember.ROLE_ADMIN);
        OrgMember member = orgMemberRepository.selectById(memberId);
        if (member == null || !member.getOrgId().equals(orgId)) throw new IllegalArgumentException("成员不存在");
        if (OrgMember.ROLE_OWNER.equals(member.getRole())) throw new IllegalArgumentException("不能修改企业主角色");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("角色不能为空");
        member.setRole(role);
        orgMemberRepository.updateById(member);
        return member;
    }

    @Transactional
    public void removeMember(Long userId, Long orgId, Long memberId) {
        orgAccessService.requireRole(userId, orgId, OrgMember.ROLE_ADMIN);
        OrgMember member = orgMemberRepository.selectById(memberId);
        if (member == null || !member.getOrgId().equals(orgId)) throw new IllegalArgumentException("成员不存在");
        if (OrgMember.ROLE_OWNER.equals(member.getRole())) throw new IllegalArgumentException("不能移除企业主");
        member.setStatus(0);
        orgMemberRepository.updateById(member);
        log.info("org member removed org={} user={}", orgId, member.getUserId());
    }

    /** 按 memberId 更新角色（自动解析用户所属企业并校验 admin） */
    @Transactional
    public OrgMember updateMemberRoleByMemberId(Long userId, Long memberId, String role) {
        OrgMember member = getMemberForUpdate(userId, memberId);
        orgAccessService.requireRole(userId, member.getOrgId(), OrgMember.ROLE_ADMIN);
        if (role == null || role.isBlank()) throw new IllegalArgumentException("角色不能为空");
        member.setRole(role);
        orgMemberRepository.updateById(member);
        return member;
    }

    /** 按 memberId 移除成员（自动解析用户所属企业并校验 admin） */
    @Transactional
    public void removeMemberByMemberId(Long userId, Long memberId) {
        OrgMember member = getMemberForUpdate(userId, memberId);
        orgAccessService.requireRole(userId, member.getOrgId(), OrgMember.ROLE_ADMIN);
        member.setStatus(0);
        orgMemberRepository.updateById(member);
        log.info("org member removed org={} user={}", member.getOrgId(), member.getUserId());
    }

    /** 校验用户为企业成员（供只读接口使用） */
    public void requireMemberAccess(Long userId, Long orgId) {
        orgAccessService.requireMember(userId, orgId);
    }

    private OrgMember getMemberForUpdate(Long userId, Long memberId) {
        Long orgId = orgAccessService.getOrgIdOf(userId);
        if (orgId == null) throw new SecurityException("您不是企业成员");
        OrgMember member = orgMemberRepository.selectById(memberId);
        if (member == null || !member.getOrgId().equals(orgId)) throw new IllegalArgumentException("成员不存在");
        if (OrgMember.ROLE_OWNER.equals(member.getRole())) throw new IllegalArgumentException("不能操作企业主");
        return member;
    }

    /** 企业统计：视频/任务/成员/存储（调用前需鉴权成员身份） */
    public Map<String, Object> orgStats(Long userId, Long orgId) {
        orgAccessService.requireMember(userId, orgId);
        return orgStatsInternal(orgId);
    }

    /** 企业统计（内部，不校验成员；Controller/Admin 已鉴权） */
    public Map<String, Object> orgStatsInternal(Long orgId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("videoTotal", videoRepository.countByOrg(orgId));
        m.put("videoCompleted", videoRepository.selectCount(new LambdaQueryWrapper<Video>()
                .eq(Video::getOrgId, orgId).eq(Video::getStatus, Video.STATUS_COMPLETED)));
        m.put("taskTotal", taskRepository.selectCount(new LambdaQueryWrapper<ProcessingTask>()
                .eq(ProcessingTask::getOrgId, orgId)));
        m.put("taskRunning", taskRepository.selectCount(new LambdaQueryWrapper<ProcessingTask>()
                .eq(ProcessingTask::getOrgId, orgId)
                .in(ProcessingTask::getStatus, ProcessingTask.STATUS_QUEUED, ProcessingTask.STATUS_RUNNING)));
        m.put("memberCount", orgMemberRepository.selectCount(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId).eq(OrgMember::getStatus, 1)));
        m.put("storageBytes", videoRepository.sumFileSizeByOrg(orgId));
        return m;
    }

    /** B2：企业任务看板 —— 状态分布 / 成功率 / 平均耗时 / 今日处理量 */
    public Map<String, Object> orgTaskStats(Long orgId) {
        List<ProcessingTask> tasks = taskRepository.selectList(
                new LambdaQueryWrapper<ProcessingTask>().eq(ProcessingTask::getOrgId, orgId));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long success = 0, totalMs = 0, durationCount = 0, todayCount = 0;
        LocalDate today = LocalDate.now();
        for (ProcessingTask t : tasks) {
            String s = t.getStatus() == null ? "unknown" : t.getStatus();
            byStatus.merge(s, 1L, Long::sum);
            if (ProcessingTask.STATUS_COMPLETED.equals(s)) success++;
            if (t.getCompletedAt() != null && t.getCreatedAt() != null) {
                totalMs += java.time.Duration.between(t.getCreatedAt(), t.getCompletedAt()).toMillis();
                durationCount++;
            }
            if (t.getCreatedAt() != null && t.getCreatedAt().toLocalDate().equals(today)) todayCount++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", tasks.size());
        m.put("byStatus", byStatus);
        m.put("successRate", tasks.isEmpty() ? 0 : Math.round(success * 1000.0 / tasks.size()) / 10.0);
        m.put("avgDurationMs", durationCount == 0 ? 0 : totalMs / durationCount);
        m.put("todayCount", todayCount);
        return m;
    }

    /** B2：企业月报 —— 视频/任务/菜谱/成员/存储/流量/费用 */
    public Map<String, Object> orgReport(Long orgId, String month) {
        java.time.YearMonth ym;
        try {
            ym = (month == null || month.isBlank()) ? java.time.YearMonth.now() : java.time.YearMonth.parse(month);
        } catch (Exception e) {
            throw new IllegalArgumentException("月份格式应为 yyyy-MM");
        }
        Map<String, Object> m = new LinkedHashMap<>(orgStatsInternal(orgId));
        m.put("month", ym.toString());
        m.put("recipesCount", recipeMetadataRepository.countByOrg(orgId));
        long totalBytes = usageRecordRepository.sumBytesByOrg(orgId);
        double totalGb = totalBytes / 1024.0 / 1024.0 / 1024.0;
        m.put("usageGb", Math.round(totalGb * 1000) / 1000.0);
        return m;
    }

    /** 企业计费摘要：套餐 + 用量(GB) + 预估费用 + 配额使用 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> billingSummary(Long orgId) {
        Org org = requireOrg(orgId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgId", org.getId());
        m.put("orgName", org.getName());
        m.put("planType", org.getPlanType());
        m.put("status", org.getStatus());
        m.put("expiredAt", org.getExpiredAt());

        long totalBytes = usageRecordRepository.sumBytesByOrg(orgId);
        double totalGb = totalBytes / 1024.0 / 1024.0 / 1024.0;
        m.put("totalBytes", totalBytes);
        m.put("totalGb", Math.round(totalGb * 1000) / 1000.0);

        Map<String, Object> quotaUse = new LinkedHashMap<>();
        try {
            if (org.getQuota() != null && !org.getQuota().isBlank()) {
                Map<String, Object> q = objectMapper.readValue(org.getQuota(), Map.class);
                quotaUse.put("videoLimit", q.getOrDefault("video_limit", 50));
                quotaUse.put("memberLimit", q.getOrDefault("member_limit", 5));
                quotaUse.put("storageLimit", q.getOrDefault("storage_bytes", 0));
            }
        } catch (Exception e) {
            log.warn("parse org quota failed org={}", orgId, e);
        }
        long videoUsed = videoRepository.countByOrg(orgId);
        long memberUsed = orgMemberRepository.selectCount(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId).eq(OrgMember::getStatus, 1));
        long storageUsed = videoRepository.sumFileSizeByOrg(orgId);
        quotaUse.put("videoUsed", videoUsed);
        quotaUse.put("memberUsed", memberUsed);
        quotaUse.put("storageUsed", storageUsed);
        // B3：配额使用百分比（前端进度条；告警阈值为 80%）
        quotaUse.put("videoPercent", pct(videoUsed, numOf(quotaUse, "videoLimit", 50)));
        quotaUse.put("memberPercent", pct(memberUsed, numOf(quotaUse, "memberLimit", 5)));
        quotaUse.put("storagePercent", pct(storageUsed, numOf(quotaUse, "storageLimit", 0)));
        m.put("quota", quotaUse);

        OrgSubscription sub = orgSubscriptionRepository.findActiveByOrg(orgId);
        m.put("subscription", sub == null ? null : Map.of(
                "planCode", sub.getPlanCode(),
                "startedAt", sub.getStartedAt(),
                "expiredAt", sub.getExpiredAt()));
        return m;
    }

    /** 企业账单列表（分页） */
    public Map<String, Object> listInvoices(Long userId, Long orgId, int page, int size) {
        orgAccessService.requireMember(userId, orgId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<Invoice> qw = new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getOrgId, orgId).orderByDesc(Invoice::getCreatedAt);
        long total = invoiceRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        return Map.of("items", invoiceRepository.selectList(qw), "total", total);
    }

    // ---------------- 订阅 ----------------

    @Transactional
    public Map<String, Object> subscribe(Long userId, Long orgId, String planCode) {
        orgAccessService.requireRole(userId, orgId, OrgMember.ROLE_OWNER);
        OrgPlan plan = orgPlanRepository.findEnabledByCode(planCode);
        if (plan == null) throw new IllegalArgumentException("套餐不存在或未启用");
        Org org = requireOrg(orgId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expired = now.plusMonths(1);

        OrgSubscription sub = OrgSubscription.builder()
                .orgId(orgId)
                .planCode(plan.getCode())
                .status("active")
                .startedAt(now)
                .expiredAt(expired)
                .build();
        orgSubscriptionRepository.insert(sub);

        org.setPlanType(plan.getCode());
        org.setQuota(plan.getQuota());
        org.setExpiredAt(expired);
        orgRepository.updateById(org);

        Invoice invoice = Invoice.builder()
                .orgId(orgId)
                .userId(userId)
                .amount(plan.getPriceMonthly())
                .currency("CNY")
                .status("pending")
                .description(plan.getName() + " 月费（" + now.format(DateTimeFormatter.ofPattern("yyyy-MM")) + "）")
                .build();
        invoiceRepository.insert(invoice);
        log.info("org subscribed org={} plan={}", orgId, planCode);
        return Map.of("orgId", orgId, "planCode", plan.getCode(), "planName", plan.getName(),
                "amount", plan.getPriceMonthly(), "expiredAt", expired);
    }

    // ---------------- Admin（平台侧企业管理） ----------------

    public Map<String, Object> adminListOrgs(int page, int size, String keyword, String status, String planType) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<Org> qw = new LambdaQueryWrapper<Org>()
                .like(keyword != null && !keyword.isBlank(), Org::getName, keyword)
                .eq(status != null && !status.isBlank(), Org::getStatus, status)
                .eq(planType != null && !planType.isBlank(), Org::getPlanType, planType)
                .orderByDesc(Org::getCreatedAt);
        long total = orgRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = orgRepository.selectList(qw).stream().map(org -> {
            Map<String, Object> m = toOrgMap(org);
            User owner = userService.getById(org.getOwnerId());
            m.put("ownerName", owner == null ? null : owner.getUsername());
            m.put("memberCount", orgMemberRepository.selectCount(new LambdaQueryWrapper<OrgMember>()
                    .eq(OrgMember::getOrgId, org.getId()).eq(OrgMember::getStatus, 1)));
            return m;
        }).collect(Collectors.toList());
        return Map.of("items", items, "total", total);
    }

    public Map<String, Object> adminOrgStats(Long orgId) {
        Org org = requireOrg(orgId);
        Map<String, Object> m = orgStatsInternal(orgId);
        m.put("recipesCount", recipeMetadataRepository.countByOrg(orgId));
        m.put("usage", billingSummary(orgId));
        return m;
    }

    @Transactional
    public Org adminSetStatus(Long orgId, Integer status) {
        Org org = requireOrg(orgId);
        org.setStatus(status);
        orgRepository.updateById(org);
        return org;
    }

    @Transactional
    public Org adminSetPlan(Long orgId, String planCode) {
        Org org = requireOrg(orgId);
        OrgPlan plan = orgPlanRepository.findEnabledByCode(planCode);
        if (plan == null) throw new IllegalArgumentException("套餐不存在或未启用");
        org.setPlanType(plan.getCode());
        org.setQuota(plan.getQuota());
        orgRepository.updateById(org);
        return org;
    }

    // ---------------- B3：Admin 账单管理 ----------------

    /** Admin 手动开票 */
    @Transactional
    public Invoice adminCreateInvoice(Long orgId, Long operatorId, String amount, String description) {
        Org org = requireOrg(orgId);
        BigDecimal amt;
        try {
            amt = new BigDecimal(amount == null ? "0" : amount.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("金额格式不正确");
        }
        if (description == null || description.isBlank()) description = "企业账单";
        Invoice inv = Invoice.builder()
                .orgId(orgId)
                .userId(operatorId)
                .amount(amt)
                .currency("CNY")
                .status("pending")
                .description(description)
                .build();
        invoiceRepository.insert(inv);
        return inv;
    }

    /** Admin 账单状态流转：pending → paid/overdue/canceled */
    @Transactional
    public Invoice adminSetInvoiceStatus(Long invoiceId, String status) {
        Invoice inv = invoiceRepository.selectById(invoiceId);
        if (inv == null) throw new IllegalArgumentException("账单不存在");
        if (!List.of("paid", "overdue", "canceled", "pending").contains(status)) {
            throw new IllegalArgumentException("非法账单状态：" + status);
        }
        inv.setStatus(status);
        if ("paid".equals(status)) inv.setPaidAt(LocalDateTime.now());
        invoiceRepository.updateById(inv);
        return inv;
    }

    /** Admin 账单列表（可按企业筛选） */
    public Map<String, Object> adminListInvoices(Long orgId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<Invoice> qw = new LambdaQueryWrapper<Invoice>()
                .eq(orgId != null, Invoice::getOrgId, orgId)
                .orderByDesc(Invoice::getCreatedAt);
        long total = invoiceRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        return Map.of("items", invoiceRepository.selectList(qw), "total", total);
    }

    /** 可用套餐列表（Admin 与订阅页共用） */
    public List<Map<String, Object>> listPlans() {
        return orgPlanRepository.selectList(new LambdaQueryWrapper<OrgPlan>()
                        .eq(OrgPlan::getStatus, 1).orderByAsc(OrgPlan::getPriceMonthly))
                .stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", p.getCode());
                    m.put("name", p.getName());
                    m.put("priceMonthly", p.getPriceMonthly());
                    m.put("quota", p.getQuota());
                    return m;
                }).collect(Collectors.toList());
    }

    private Map<String, Object> toOrgMap(Org org) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", org.getId());
        m.put("name", org.getName());
        m.put("code", org.getCode());
        m.put("ownerId", org.getOwnerId());
        m.put("logo", org.getLogo());
        m.put("planType", org.getPlanType());
        m.put("status", org.getStatus());
        m.put("expiredAt", org.getExpiredAt());
        m.put("createdAt", org.getCreatedAt());
        return m;
    }

    /** 配额使用百分比（limit<=0 返回 null） */
    private Integer pct(long used, long limit) {
        if (limit <= 0) return null;
        return (int) Math.min(100, Math.round(used * 100.0 / limit));
    }

    private long numOf(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v == null) return def;
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}
