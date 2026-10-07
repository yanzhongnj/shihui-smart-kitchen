package com.videostep.locator.service;

import com.videostep.locator.entity.RecycleSetting;
import com.videostep.locator.repository.CookingCheckinRepository;
import com.videostep.locator.repository.CookingPlanRepository;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.repository.DishNutritionRepository;
import com.videostep.locator.repository.MealPhotoRepository;
import com.videostep.locator.repository.RecycleSettingRepository;
import com.videostep.locator.repository.UserWorkRepository;
import com.videostep.locator.repository.XunweiMemoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 回收站超期自动清理：软删除记录超过保留期（默认 90 天）后自动「归档 + 彻底删除」
 * （复用 RecycleService.purge 的归档闭环与审计）。
 *
 * <p>管理员可操作（/api/admin/recycle/**）：
 * - 查看/修改保留期与执行 cron（recycle_settings 表，运行时生效，无需重启）；
 * - 启停自动任务（enabled，停用不影响手动触发）；
 * - 手动触发一次（purgeNow，操作员记入归档/审计）。</p>
 *
 * <p>定时执行：每 60s tick 一次，按配置 cron 判断是否到点（改 cron 在一分钟内生效）；
 * 系统任务 operatorId=null（归档 archived_by 为 NULL、审计 operator_id 为 NULL）；手动触发传操作员 ID。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecyclePurgeJob {

    private static final int BATCH_SIZE = 200;
    private static final Long SETTINGS_ID = 1L;

    private final RecycleService recycleService;
    private final CookingCheckinRepository checkinRepository;
    private final CookingPlanRepository planRepository;
    private final DietRecordRepository dietRecordRepository;
    private final UserWorkRepository workRepository;
    private final XunweiMemoryRepository memoryRepository;
    private final MealPhotoRepository mealPhotoRepository;
    private final DishNutritionRepository dishNutritionRepository;
    private final RecycleSettingRepository settingRepository;

    @Value("${app.recycle.retention-days:90}")
    private int defaultRetentionDays;

    @Value("${app.recycle.purge-cron:0 30 3 * * *}")
    private String defaultPurgeCron;

    /** 启动时确保配置行存在（id=1），后续运行时读取 */
    @PostConstruct
    public void ensureSettings() {
        if (settingRepository.selectById(SETTINGS_ID) == null) {
            RecycleSetting s = RecycleSetting.builder()
                    .id(SETTINGS_ID)
                    .retentionDays(Math.max(1, defaultRetentionDays))
                    .purgeCron(defaultPurgeCron)
                    .enabled(1)
                    .lastRunCount(0)
                    .build();
            settingRepository.insert(s);
            log.info("[recycle] 初始化清理配置 retentionDays={} cron={}", s.getRetentionDays(), s.getPurgeCron());
        }
    }

    /** 最近一次自动执行时间（用于 cron 到点判断，重启后为 null 不追跑） */
    private volatile LocalDateTime lastAutoFire;

    /** 每 60s tick：按配置 cron 判断是否到点（改 cron 在一分钟内生效） */
    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        purgeExpiredIfDue();
    }

    /** 到点判断 + 自动清理：停用（enabled=0）或未到 cron 时间则跳过；系统任务 operator=null */
    public synchronized void purgeExpiredIfDue() {
        RecycleSetting s = loadSettings();
        if (s.getEnabled() == null || s.getEnabled() != 1) {
            return;
        }
        String cron = (s.getPurgeCron() == null || s.getPurgeCron().isBlank())
                ? defaultPurgeCron : s.getPurgeCron().trim();
        CronExpression expr;
        try {
            expr = CronExpression.parse(cron);
        } catch (Exception e) {
            log.warn("[recycle] cron 不合法，跳过自动清理: {}", e.getMessage());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime base = lastAutoFire == null ? now.minusMinutes(1) : lastAutoFire;
        LocalDateTime next = expr.next(base);
        if (next == null || now.isBefore(next)) {
            return; // 未到执行时间
        }
        lastAutoFire = now;
        Map<String, Object> result = runPurge(null, s.getRetentionDays());
        saveLastRun(result, null);
        log.info("[recycle] 自动清理完成 total={} cron={}", result.get("total"), cron);
    }

    /** 管理员手动触发一次（不受 enabled 影响），返回清理统计并记录 last_run */
    public Map<String, Object> purgeNow(Long operatorId) {
        RecycleSetting s = loadSettings();
        Map<String, Object> result = runPurge(operatorId, s.getRetentionDays());
        saveLastRun(result, operatorId);
        log.info("[recycle] 手动触发清理完成 total={} by={}", result.get("total"), operatorId);
        return result;
    }

    /** 执行清理：遍历各类型，逐条归档+彻底删除；返回 {total, before, byType} */
    private Map<String, Object> runPurge(Long operatorId, int retentionDays) {
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        Map<String, Integer> byType = new LinkedHashMap<>();
        int total = 0;
        total += purgeType(RecycleService.TYPE_CHECKINS,
                (o, sz) -> checkinRepository.findExpiredIds(before, o, sz), operatorId, byType);
        total += purgeType(RecycleService.TYPE_PLANS,
                (o, sz) -> planRepository.findExpiredIds(before, o, sz), operatorId, byType);
        total += purgeType(RecycleService.TYPE_DIET,
                (o, sz) -> dietRecordRepository.findExpiredIds(before, o, sz), operatorId, byType);
        total += purgeType(RecycleService.TYPE_WORKS,
                (o, sz) -> workRepository.findExpiredIds(before, o, sz), operatorId, byType);
        total += purgeType(RecycleService.TYPE_MEMORIES,
                (o, sz) -> memoryRepository.findExpiredIds(before, o, sz), operatorId, byType);
        total += purgeType(RecycleService.TYPE_PHOTOS,
                (o, sz) -> mealPhotoRepository.findExpiredIds(before, o, sz), operatorId, byType);
        total += purgeType(RecycleService.TYPE_DISHES,
                (o, sz) -> dishNutritionRepository.findExpiredIds(before, o, sz), operatorId, byType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("before", before.toString());
        out.put("byType", byType);
        return out;
    }

    private int purgeType(String type, BiFunction<Integer, Integer, List<Long>> idFetcher,
                          Long operatorId, Map<String, Integer> byType) {
        int purged = 0;
        for (int offset = 0; ; offset += BATCH_SIZE) {
            List<Long> ids;
            try {
                ids = idFetcher.apply(offset, BATCH_SIZE);
            } catch (Exception e) {
                log.warn("[recycle] 超期清理查询失败 type={}: {}", type, e.getMessage());
                break;
            }
            if (ids == null || ids.isEmpty()) break;
            for (Long id : ids) {
                try {
                    recycleService.purge(type, id, operatorId);
                    purged++;
                } catch (Exception e) {
                    log.warn("[recycle] 超期清理单条失败 type={} id={}: {}", type, id, e.getMessage());
                }
            }
        }
        byType.put(type, purged);
        return purged;
    }

    // ---------------- 配置（管理员可操作） ----------------

    /** 读取配置 */
    public Map<String, Object> settings() {
        RecycleSetting s = loadSettings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retentionDays", s.getRetentionDays());
        m.put("purgeCron", s.getPurgeCron());
        m.put("enabled", s.getEnabled() != null && s.getEnabled() == 1);
        m.put("lastRunAt", s.getLastRunAt());
        m.put("lastRunCount", s.getLastRunCount() == null ? 0 : s.getLastRunCount());
        m.put("lastRunDetail", s.getLastRunDetail());
        m.put("updatedBy", s.getUpdatedBy());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    /** 更新配置（retentionDays / purgeCron / enabled 任一非空即更新）；cron 校验 */
    public Map<String, Object> updateSettings(Integer retentionDays, String purgeCron, Boolean enabled, Long operatorId) {
        if (retentionDays != null && (retentionDays < 1 || retentionDays > 3650)) {
            throw new IllegalArgumentException("保留期须在 1~3650 天之间");
        }
        String cron = (purgeCron == null || purgeCron.isBlank()) ? null : purgeCron.trim();
        if (cron != null) {
            try {
                CronExpression.parse(cron);
            } catch (Exception e) {
                throw new IllegalArgumentException("cron 表达式不合法：" + e.getMessage());
            }
        }
        RecycleSetting s = loadSettings();
        if (retentionDays != null) s.setRetentionDays(retentionDays);
        if (cron != null) s.setPurgeCron(cron);
        if (enabled != null) s.setEnabled(enabled ? 1 : 0);
        s.setUpdatedBy(operatorId);
        settingRepository.updateById(s);
        log.info("[recycle] 更新清理配置 retentionDays={} cron={} enabled={} by={}",
                s.getRetentionDays(), s.getPurgeCron(), s.getEnabled(), operatorId);
        return settings();
    }

    private RecycleSetting loadSettings() {
        RecycleSetting s = settingRepository.selectById(SETTINGS_ID);
        if (s == null) {
            ensureSettings();
            s = settingRepository.selectById(SETTINGS_ID);
        }
        if (s.getRetentionDays() == null) s.setRetentionDays(Math.max(1, defaultRetentionDays));
        if (s.getPurgeCron() == null || s.getPurgeCron().isBlank()) s.setPurgeCron(defaultPurgeCron);
        if (s.getEnabled() == null) s.setEnabled(1);
        return s;
    }

    private void saveLastRun(Map<String, Object> result, Long operatorId) {
        try {
            RecycleSetting s = loadSettings();
            s.setLastRunAt(LocalDateTime.now());
            s.setLastRunCount(((Number) result.get("total")).intValue());
            s.setLastRunDetail(formatDetail((Map<?, ?>) result.get("byType")));
            s.setUpdatedBy(operatorId);
            settingRepository.updateById(s);
        } catch (Exception e) {
            log.warn("[recycle] 记录执行结果失败: {}", e.getMessage());
        }
    }

    private String formatDetail(Map<?, ?> byType) {
        Map<String, String> label = new LinkedHashMap<>();
        label.put(RecycleService.TYPE_CHECKINS, "打卡");
        label.put(RecycleService.TYPE_DIET, "饮食");
        label.put(RecycleService.TYPE_PLANS, "计划");
        label.put(RecycleService.TYPE_WORKS, "作品");
        label.put(RecycleService.TYPE_MEMORIES, "记忆");
        label.put(RecycleService.TYPE_PHOTOS, "拍照");
        label.put(RecycleService.TYPE_DISHES, "菜品");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> e : byType.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (sb.length() > 0) sb.append('，');
            sb.append(label.getOrDefault(k, k)).append(' ').append(e.getValue()).append(" 条");
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }
}
