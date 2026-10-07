package com.videostep.locator.service.health;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.HealthProfile;
import com.videostep.locator.entity.WeightLog;
import com.videostep.locator.repository.HealthProfileRepository;
import com.videostep.locator.repository.WeightLogRepository;
import com.videostep.locator.service.GrowthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 体重追踪（营养健康模块 P3，设计文档 §5.6）：
 * 记录（同人同日 upsert）+ 最近 N 天趋势 + 联动 target_weight 进度 + 连续记录徽章。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeightService {

    private final WeightLogRepository repository;
    private final HealthProfileRepository profileRepository;
    private final GrowthService growthService;

    /** 记录/更新体重（同日 upsert）；记录后校验连续徽章 */
    public Map<String, Object> record(Long userId, BigDecimal weight, LocalDate date) {
        if (weight == null || weight.doubleValue() <= 0) {
            throw new IllegalArgumentException("请输入有效的体重");
        }
        LocalDate d = date == null ? LocalDate.now() : date;
        WeightLog existing = repository.selectOne(new LambdaQueryWrapper<WeightLog>()
                .eq(WeightLog::getUserId, userId)
                .eq(WeightLog::getLogDate, d));
        WeightLog row;
        if (existing != null) {
            existing.setWeight(weight);
            repository.updateById(existing);
            row = existing;
        } else {
            row = WeightLog.builder()
                    .userId(userId)
                    .weight(weight)
                    .logDate(d)
                    .build();
            repository.insert(row);
        }
        List<Map<String, Object>> newBadges = growthService.checkBadges(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("date", row.getLogDate().toString());
        out.put("weight", row.getWeight());
        out.put("newBadges", newBadges);
        return out;
    }

    /** 最近 N 天趋势 + 目标进度 + 连续记录天数 */
    public Map<String, Object> trend(Long userId, int days) {
        int n = Math.max(1, Math.min(days, 90));
        LocalDate start = LocalDate.now().minusDays(n - 1L);
        List<WeightLog> list = repository.findSince(userId, start);
        List<Map<String, Object>> items = new ArrayList<>();
        for (WeightLog w : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", w.getLogDate().toString());
            m.put("weight", w.getWeight());
            items.add(m);
        }

        HealthProfile profile = profileRepository.selectOne(new LambdaQueryWrapper<HealthProfile>()
                .eq(HealthProfile::getUserId, userId));
        WeightLog latest = repository.findLatest(userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", n);
        out.put("items", items);
        out.put("count", list.size());
        out.put("streak", calcStreak(userId));
        out.put("latestWeight", latest != null ? latest.getWeight() : null);
        out.put("targetWeight", profile != null ? profile.getTargetWeight() : null);
        out.put("startWeight", profile != null ? profile.getWeight() : null);
        // 进度
        if (latest != null && profile != null && profile.getTargetWeight() != null
                && profile.getWeight() != null) {
            double startW = profile.getWeight().doubleValue();
            double curW = latest.weight();
            double targetW = profile.getTargetWeight().doubleValue();
            double lost = startW - curW;
            double remaining = curW - targetW;
            out.put("delta", round1(lost));       // 相对建档体重变化（+减 / -增）
            out.put("remaining", round1(remaining)); // 距离目标剩余（正=还需减）
            if (Math.abs(startW - targetW) > 0.01) {
                double progress = (lost / (startW - targetW)) * 100;
                out.put("progress", (int) Math.max(0, Math.min(100, progress)));
            } else {
                out.put("progress", 100);
            }
        } else {
            out.put("delta", null);
            out.put("remaining", null);
            out.put("progress", null);
        }
        return out;
    }

    /** 连续记录天数：今天有则从今天起算，否则从昨天起算 */
    public int calcStreak(Long userId) {
        List<WeightLog> logs = repository.findSince(userId, LocalDate.now().minusDays(120));
        if (logs.isEmpty()) return 0;
        Set<LocalDate> set = new HashSet<>();
        for (WeightLog w : logs) set.add(w.getLogDate());
        LocalDate today = LocalDate.now();
        LocalDate cur = set.contains(today) ? today : today.minusDays(1);
        if (!set.contains(cur)) return 0;
        int streak = 0;
        while (set.contains(cur)) {
            streak++;
            cur = cur.minusDays(1);
        }
        return streak;
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
