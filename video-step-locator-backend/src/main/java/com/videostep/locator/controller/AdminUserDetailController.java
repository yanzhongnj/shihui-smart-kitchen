package com.videostep.locator.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.CookingCheckin;
import com.videostep.locator.entity.CookingPlan;
import com.videostep.locator.entity.DietRecord;
import com.videostep.locator.entity.User;
import com.videostep.locator.entity.UserFavorite;
import com.videostep.locator.entity.UserPreference;
import com.videostep.locator.entity.UserWork;
import com.videostep.locator.entity.WeightLog;
import com.videostep.locator.repository.CookingCheckinRepository;
import com.videostep.locator.repository.CookingPlanRepository;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.repository.UserFavoriteRepository;
import com.videostep.locator.repository.UserWorkRepository;
import com.videostep.locator.repository.WeightLogRepository;
import com.videostep.locator.service.CheckinService;
import com.videostep.locator.service.FavoriteService;
import com.videostep.locator.service.GrowthService;
import com.videostep.locator.service.PlanService;
import com.videostep.locator.service.PreferenceService;
import com.videostep.locator.service.UserService;
import com.videostep.locator.service.WorkService;
import com.videostep.locator.service.health.DietRecordService;
import com.videostep.locator.service.health.HealthProfileService;
import com.videostep.locator.service.health.WeightService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M2：个人用户管理（仅 ADMIN 角色）。统一 /api/admin/users/{id}/...
 *
 * <p>全部复用现有用户级 Service（传入目标 userId）或直接查询 repository，
 * <b>不修改任何 APP 在用接口/服务逻辑</b>；删除类操作直接操作 repository
 * （绕开用户级属主校验），管理员在 Web 端均需二次确认。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserDetailController {

    private final UserService userService;
    private final CheckinService checkinService;
    private final HealthProfileService healthProfileService;
    private final DietRecordService dietRecordService;
    private final PlanService planService;
    private final FavoriteService favoriteService;
    private final PreferenceService preferenceService;
    private final WorkService workService;
    private final GrowthService growthService;
    private final WeightService weightService;

    private final CookingCheckinRepository checkinRepository;
    private final DietRecordRepository dietRecordRepository;
    private final CookingPlanRepository planRepository;
    private final UserWorkRepository workRepository;
    private final UserFavoriteRepository favoriteRepository;
    private final WeightLogRepository weightLogRepository;

    // ---------------- 概览 ----------------

    /** 用户信息 + 成长（等级/EXP/徽章）+ 活跃计数汇总 */
    @GetMapping("/users/{id}")
    public ApiResponse<Map<String, Object>> userDetail(@PathVariable Long id) {
        User u = userService.getById(id);
        if (u == null) return ApiResponse.error(404, "用户不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        m.put("status", u.getStatus());
        m.put("createdAt", u.getCreatedAt());
        m.put("updatedAt", u.getUpdatedAt());
        m.put("growth", growthService.getLevelInfo(id));
        m.put("badges", growthService.getBadges(id));
        m.put("checkinCount", checkinRepository.selectCount(
                new LambdaQueryWrapper<CookingCheckin>().eq(CookingCheckin::getUserId, id)));
        m.put("workCount", workRepository.selectCount(
                new LambdaQueryWrapper<UserWork>().eq(UserWork::getUserId, id)));
        m.put("favoriteCount", favoriteRepository.selectCount(
                new LambdaQueryWrapper<UserFavorite>().eq(UserFavorite::getUserId, id)));
        m.put("planCount", planRepository.selectCount(
                new LambdaQueryWrapper<CookingPlan>().eq(CookingPlan::getUserId, id)));
        m.put("dietCount", dietRecordRepository.selectCount(
                new LambdaQueryWrapper<DietRecord>().eq(DietRecord::getUserId, id)));
        m.put("weightCount", weightLogRepository.selectCount(
                new LambdaQueryWrapper<WeightLog>().eq(WeightLog::getUserId, id)));
        return ApiResponse.success(m);
    }

    // ---------------- 打卡 ----------------

    /** 打卡记录（按月，month 形如 2026-08） */
    @GetMapping("/users/{id}/checkins")
    public ApiResponse<List<Map<String, Object>>> checkins(
            @PathVariable Long id, @RequestParam(required = false) String month) {
        return ApiResponse.success(checkinService.listByMonth(id, month));
    }

    /** 删除打卡（管理员；级联删除自动生成的饮食记录） */
    @DeleteMapping("/checkins/{checkinId}")
    public ApiResponse<Map<String, Object>> deleteCheckin(@PathVariable Long checkinId) {
        CookingCheckin c = checkinRepository.selectById(checkinId);
        if (c == null) return ApiResponse.error(404, "打卡记录不存在");
        try {
            dietRecordRepository.deleteByCheckinId(c.getUserId(), checkinId);
        } catch (Exception e) {
            log.warn("[admin] 打卡级联删除饮食失败 checkin={}: {}", checkinId, e.getMessage());
        }
        checkinRepository.softDeleteById(checkinId); // 软删 + 记录 deleted_at
        return ApiResponse.success(Map.of("deleted", true));
    }

    // ---------------- 健康档案 ----------------

    /** 健康档案（含目标） */
    @GetMapping("/users/{id}/health-profile")
    public ApiResponse<Map<String, Object>> healthProfile(@PathVariable Long id) {
        return ApiResponse.success(healthProfileService.profileMap(id));
    }

    // ---------------- 饮食记录 ----------------

    /** 饮食记录（date 可选 yyyy-MM-dd；分页） */
    @GetMapping("/users/{id}/diet")
    public ApiResponse<Map<String, Object>> diet(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(dietRecordService.history(id, date, page, size));
    }

    /** 删除饮食记录 */
    @DeleteMapping("/diet/{dietId}")
    public ApiResponse<Map<String, Object>> deleteDiet(@PathVariable Long dietId) {
        DietRecord d = dietRecordRepository.selectById(dietId);
        if (d == null) return ApiResponse.error(404, "饮食记录不存在");
        dietRecordRepository.softDeleteById(dietId); // 软删 + 记录 deleted_at
        return ApiResponse.success(Map.of("deleted", true));
    }

    // ---------------- 体重跟踪 ----------------

    /** 体重日志 + 趋势（最近 N 天，默认 30） */
    @GetMapping("/users/{id}/weight-logs")
    public ApiResponse<Map<String, Object>> weightLogs(
            @PathVariable Long id, @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(weightService.trend(id, days));
    }

    // ---------------- 做饭计划 ----------------

    /** 做饭计划（周计划 + 条目完成状态） */
    @GetMapping("/users/{id}/plans")
    public ApiResponse<List<Map<String, Object>>> plans(@PathVariable Long id) {
        return ApiResponse.success(planService.list(id));
    }

    /** 删除做饭计划（条目 FK ON DELETE CASCADE） */
    @DeleteMapping("/plans/{planId}")
    public ApiResponse<Map<String, Object>> deletePlan(@PathVariable Long planId) {
        CookingPlan p = planRepository.selectById(planId);
        if (p == null) return ApiResponse.error(404, "计划不存在");
        planRepository.softDeleteById(planId); // 软删 + 记录 deleted_at
        return ApiResponse.success(Map.of("deleted", true));
    }

    // ---------------- 收藏 ----------------

    /** 收藏列表（视频/菜谱） */
    @GetMapping("/users/{id}/favorites")
    public ApiResponse<List<Map<String, Object>>> favorites(@PathVariable Long id) {
        return ApiResponse.success(favoriteService.list(id));
    }

    // ---------------- 口味/忌口（可编辑，辅助客服/风控） ----------------

    /** 口味/忌口/偏好（无记录时返回默认） */
    @GetMapping("/users/{id}/preferences")
    public ApiResponse<Map<String, Object>> preferences(@PathVariable Long id) {
        UserPreference p = preferenceService.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", id);
        m.put("spiceLevel", p.getSpiceLevel());
        m.put("tabooAllergens", p.getTabooAllergens());
        m.put("tabooIngredients", p.getTabooIngredients());
        m.put("preferredTastes", p.getPreferredTastes());
        m.put("preferredCuisines", p.getPreferredCuisines());
        m.put("maxCalories", p.getMaxCalories());
        m.put("notes", p.getNotes());
        return ApiResponse.success(m);
    }

    /** 编辑口味/忌口 */
    @PutMapping("/users/{id}/preferences")
    public ApiResponse<Map<String, Object>> updatePreferences(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        UserPreference up = new UserPreference();
        up.setSpiceLevel(body.get("spiceLevel") instanceof Number n ? n.intValue() : null);
        up.setTabooAllergens(str(body.get("tabooAllergens")));
        up.setTabooIngredients(str(body.get("tabooIngredients")));
        up.setPreferredTastes(str(body.get("preferredTastes")));
        up.setPreferredCuisines(str(body.get("preferredCuisines")));
        up.setMaxCalories(body.get("maxCalories") instanceof Number n ? n.intValue() : null);
        up.setNotes(str(body.get("notes")));
        UserPreference saved = preferenceService.update(id, up);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", id);
        m.put("spiceLevel", saved.getSpiceLevel());
        m.put("tabooAllergens", saved.getTabooAllergens());
        m.put("tabooIngredients", saved.getTabooIngredients());
        m.put("preferredTastes", saved.getPreferredTastes());
        m.put("preferredCuisines", saved.getPreferredCuisines());
        m.put("maxCalories", saved.getMaxCalories());
        m.put("notes", saved.getNotes());
        return ApiResponse.success(m);
    }

    // ---------------- 作品 ----------------

    /** 做菜作品列表（含图片） */
    @GetMapping("/users/{id}/works")
    public ApiResponse<List<Map<String, Object>>> works(@PathVariable Long id) {
        return ApiResponse.success(workService.listMine(id));
    }

    /** 删除作品（软删除，保留磁盘图片以便可恢复） */
    @DeleteMapping("/works/{workId}")
    public ApiResponse<Map<String, Object>> deleteWork(@PathVariable Long workId) {
        UserWork w = workRepository.selectById(workId);
        if (w == null) return ApiResponse.error(404, "作品不存在");
        workRepository.softDeleteById(workId); // 软删 + 记录 deleted_at（保留磁盘图片可恢复）
        log.info("[admin] 软删除作品 work={} userId={}", workId, w.getUserId());
        return ApiResponse.success(Map.of("deleted", true));
    }

    // ---------------- 成长 ----------------

    /** 成长信息：等级/EXP/进度/近期经验日志 + 徽章 */
    @GetMapping("/users/{id}/growth")
    public ApiResponse<Map<String, Object>> growth(@PathVariable Long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("levelInfo", growthService.getLevelInfo(id));
        m.put("badges", growthService.getBadges(id));
        return ApiResponse.success(m);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
