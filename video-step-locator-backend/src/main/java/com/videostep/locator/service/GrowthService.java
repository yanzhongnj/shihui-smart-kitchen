package com.videostep.locator.service;

import com.videostep.locator.entity.UserBadge;
import com.videostep.locator.entity.UserExpLog;
import com.videostep.locator.entity.WeightLog;
import com.videostep.locator.repository.CookingCheckinRepository;
import com.videostep.locator.repository.UserBadgeRepository;
import com.videostep.locator.repository.UserExpLogRepository;
import com.videostep.locator.repository.WeightLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 厨艺成长体系：等级 EXP + 成就徽章（Keep 式即时反馈）。
 * 等级阈值：Lv(k) 所需累计经验 = 100 * k*(k-1)/2（Lv1=0, Lv2=100, Lv3=300, Lv4=600, Lv5=1000...）。
 * 徽章规则硬编码于此（badge defs），查询/发放幂等。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GrowthService {

    private final UserExpLogRepository expLogRepository;
    private final UserBadgeRepository badgeRepository;
    private final CookingCheckinRepository checkinRepository;
    private final WeightLogRepository weightLogRepository;

    // ---------- 等级 ----------
    private static final String[] LEVEL_NAMES = {
            "入门学徒", "厨房新手", "家常快手", "掌勺新手", "熟练主厨",
            "进阶厨师", "资深大厨", "私房高手", "大厨级", "食神"
    };

    /** 累计经验 → 等级（>=1） */
    static int levelForExp(long exp) {
        int level = 1;
        while (level <= 100) {
            long t = 100L * level * (level - 1) / 2;
            if (exp < t) break;
            level++;
        }
        return Math.max(1, level - 1);
    }

    /** 达到某等级所需累计经验 */
    static long thresholdForLevel(int level) {
        if (level <= 1) return 0;
        return 100L * level * (level - 1) / 2;
    }

    public Map<String, Object> getLevelInfo(Long userId) {
        long totalExp = expLogRepository.sumExp(userId);
        int level = levelForExp(totalExp);
        long curThreshold = thresholdForLevel(level);
        long nextThreshold = thresholdForLevel(level + 1);
        long intoLevel = totalExp - curThreshold;
        long levelSpan = nextThreshold - curThreshold;
        int progress = levelSpan <= 0 ? 100 : (int) Math.min(100, intoLevel * 100 / levelSpan);

        List<Map<String, Object>> recent = expLogRepository.findRecent(userId, 10).stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("delta", e.getDelta());
                    m.put("reason", e.getReason());
                    m.put("createdAt", e.getCreatedAt());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("level", level);
        result.put("levelName", levelName(level));
        result.put("totalExp", totalExp);
        result.put("curThreshold", curThreshold);
        result.put("nextThreshold", nextThreshold);
        result.put("progress", progress);
        result.put("recentLogs", recent);
        return result;
    }

    /** 加经验并顺带校验徽章（幂等、不抛异常、不影响主流程） */
    public void addExp(Long userId, int delta, String reason, Long refId) {
        if (userId == null || delta <= 0) return;
        try {
            expLogRepository.insert(UserExpLog.builder()
                    .userId(userId).delta(delta).reason(reason).refId(refId).build());
            checkBadges(userId);
        } catch (Exception e) {
            log.warn("addExp failed userId={}: {}", userId, e.getMessage());
        }
    }

    // ---------- 徽章 ----------
    private record BadgeDef(String code, String name, String desc, String icon) {
    }

    private static final List<BadgeDef> BADGE_DEFS = List.of(
            new BadgeDef("first_checkin", "初次下厨", "完成第一次炒菜打卡", "🍳"),
            new BadgeDef("checkin_streak_3", "三日之约", "连续打卡 3 天", "🔥"),
            new BadgeDef("checkin_streak_7", "一周坚守", "连续打卡 7 天", "🏆"),
            new BadgeDef("checkin_total_10", "十次下厨", "累计打卡 10 次", "🍜"),
            new BadgeDef("checkin_total_30", "三十天坚持", "累计打卡 30 次", "🥇"),
            new BadgeDef("recipes_5", "初识百味", "打卡过 5 道不同菜", "🍲"),
            new BadgeDef("recipes_15", "厨艺进阶", "打卡过 15 道不同菜", "👨🍳"),
            new BadgeDef("exp_300", "经验积累", "累计获得 300 经验", "⭐"),
            new BadgeDef("level_3", "厨艺小成", "达到厨艺 3 级", "🎖️"),
            new BadgeDef("weight_log_7", "七日称重", "连续记录体重 7 天", "⚖️")
    );

    /** 全部徽章定义 + 是否已获得 */
    public List<Map<String, Object>> getBadges(Long userId) {
        Set<String> owned = new HashSet<>(badgeRepository.findBadgeCodesByUser(userId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (BadgeDef def : BADGE_DEFS) {
            Map<String, Object> m = new HashMap<>();
            m.put("code", def.code());
            m.put("name", def.name());
            m.put("desc", def.desc());
            m.put("icon", def.icon());
            m.put("obtained", owned.contains(def.code()));
            list.add(m);
        }
        return list;
    }

    /** 检查并发放新徽章；返回本次新获得的徽章列表（空 = 无新徽章）。幂等。 */
    public List<Map<String, Object>> checkBadges(Long userId) {
        List<Map<String, Object>> newly = new ArrayList<>();
        if (userId == null) return newly;
        try {
            long exp = expLogRepository.sumExp(userId);
            long totalCount = checkinRepository.countTotal(userId);
            long distinctRecipes = checkinRepository.countDistinctRecipes(userId);
            int streak = calcStreak(userId);
            int level = levelForExp(exp);

            Set<String> owned = new HashSet<>(badgeRepository.findBadgeCodesByUser(userId));
            for (BadgeDef def : BADGE_DEFS) {
                if (owned.contains(def.code())) continue;
                boolean earn = switch (def.code()) {
                    case "first_checkin" -> totalCount >= 1;
                    case "checkin_streak_3" -> streak >= 3;
                    case "checkin_streak_7" -> streak >= 7;
                    case "checkin_total_10" -> totalCount >= 10;
                    case "checkin_total_30" -> totalCount >= 30;
                    case "recipes_5" -> distinctRecipes >= 5;
                    case "recipes_15" -> distinctRecipes >= 15;
                    case "exp_300" -> exp >= 300;
                    case "level_3" -> level >= 3;
                    case "weight_log_7" -> weightStreak(userId) >= 7;
                    default -> false;
                };
                if (earn) {
                    boolean ok = true;
                    try {
                        badgeRepository.insert(UserBadge.builder()
                                .userId(userId).badgeCode(def.code()).build());
                    } catch (Exception e) {
                        // 唯一键冲突 = 已发放，忽略
                        ok = false;
                    }
                    if (ok) {
                        owned.add(def.code());
                        Map<String, Object> m = new HashMap<>();
                        m.put("code", def.code());
                        m.put("name", def.name());
                        m.put("desc", def.desc());
                        m.put("icon", def.icon());
                        newly.add(m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("checkBadges failed userId={}: {}", userId, e.getMessage());
        }
        return newly;
    }

    /** 连续打卡天数：今天已打卡从今天起算，否则从昨天起算；断了返回 0 */
    public int calcStreak(Long userId) {
        try {
            List<LocalDate> dates = checkinRepository.findDistinctDates(userId);
            if (dates.isEmpty()) return 0;
            Set<LocalDate> set = new HashSet<>(dates);
            LocalDate today = LocalDate.now();
            LocalDate cur = set.contains(today) ? today : today.minusDays(1);
            if (!set.contains(cur)) return 0;
            int streak = 0;
            while (set.contains(cur)) {
                streak++;
                cur = cur.minusDays(1);
            }
            return streak;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 连续记录体重天数（体重日志，今天有则从今天起算） */
    private int weightStreak(Long userId) {
        try {
            List<WeightLog> logs = weightLogRepository.findSince(userId, LocalDate.now().minusDays(120));
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
        } catch (Exception e) {
            return 0;
        }
    }

    private static String levelName(int level) {
        if (level <= 0) return LEVEL_NAMES[0];
        int idx = Math.min(level - 1, LEVEL_NAMES.length - 1);
        return LEVEL_NAMES[idx];
    }
}
