package com.videostep.locator.service;

import com.videostep.locator.entity.CookingCheckin;
import com.videostep.locator.entity.MealPhoto;
import com.videostep.locator.repository.AdminAuditLogRepository;
import com.videostep.locator.repository.CookingCheckinRepository;
import com.videostep.locator.repository.CookingPlanRepository;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.repository.DishNutritionRepository;
import com.videostep.locator.repository.MealPhotoRepository;
import com.videostep.locator.repository.UserWorkRepository;
import com.videostep.locator.repository.XunweiMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局回收站服务（软删除记录治理，仅 ADMIN 调用）。
 *
 * <p>覆盖：cooking_checkins / diet_records / cooking_plans / user_works / xunwei_memory /
 * meal_photos / dish_nutrition_db 七张软删表。
 * 操作：恢复（deleted 1→0，打卡级联恢复饮食）、彻底删除（先归档到 *_archive 表再物理删）。
 * 每次恢复/彻底删除写入 admin_audit_log 审计。</p>
 *
 * <p>全部使用原生 SQL：MyBatis-Plus 逻辑删除开启后 selectById/selectList 会自动过滤 deleted=1、
 * deleteById 会被转成软删，因此列已删行/恢复/物理删必须走自定义 @Select/@Update/@Delete。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecycleService {

    public static final String TYPE_CHECKINS = "checkins";
    public static final String TYPE_DIET = "diet";
    public static final String TYPE_PLANS = "plans";
    public static final String TYPE_WORKS = "works";
    public static final String TYPE_MEMORIES = "memories";
    public static final String TYPE_PHOTOS = "photos";
    public static final String TYPE_DISHES = "dishes";

    private final CookingCheckinRepository checkinRepository;
    private final CookingPlanRepository planRepository;
    private final DietRecordRepository dietRecordRepository;
    private final UserWorkRepository workRepository;
    private final XunweiMemoryRepository memoryRepository;
    private final MealPhotoRepository mealPhotoRepository;
    private final DishNutritionRepository dishNutritionRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final FileStorageService fileStorageService;

    /** 已软删记录分页（含用户名），type ∈ checkins/diet/plans/works */
    public Map<String, Object> listDeleted(String type, Long userId, String keyword, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = safePage * safeSize;
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        List<Map<String, Object>> items;
        long total;
        switch (type) {
            case TYPE_CHECKINS -> {
                items = checkinRepository.findDeletedPage(userId, kw, offset, safeSize);
                total = checkinRepository.countDeleted(userId, kw);
            }
            case TYPE_DIET -> {
                items = dietRecordRepository.findDeletedPage(userId, kw, offset, safeSize);
                total = dietRecordRepository.countDeleted(userId, kw);
            }
            case TYPE_PLANS -> {
                items = planRepository.findDeletedPage(userId, kw, offset, safeSize);
                total = planRepository.countDeleted(userId, kw);
            }
            case TYPE_WORKS -> {
                items = workRepository.findDeletedPage(userId, kw, offset, safeSize);
                total = workRepository.countDeleted(userId, kw);
            }
            case TYPE_MEMORIES -> {
                items = memoryRepository.findDeletedPage(userId, kw, offset, safeSize);
                total = memoryRepository.countDeleted(userId, kw);
            }
            case TYPE_PHOTOS -> {
                items = mealPhotoRepository.findDeletedPage(userId, kw, offset, safeSize);
                total = mealPhotoRepository.countDeleted(userId, kw);
            }
            case TYPE_DISHES -> {
                // 菜品库为全局参考数据，忽略 userId；keyword 匹配菜名/来源
                items = dishNutritionRepository.findDeletedPage(kw, offset, safeSize);
                total = dishNutritionRepository.countDeleted(kw);
            }
            default -> throw new IllegalArgumentException("未知回收站类型: " + type);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    /** 各类型已删数量（菜单角标） */
    public Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(TYPE_CHECKINS, checkinRepository.countDeleted(null, null));
        m.put(TYPE_DIET, dietRecordRepository.countDeleted(null, null));
        m.put(TYPE_PLANS, planRepository.countDeleted(null, null));
        m.put(TYPE_WORKS, workRepository.countDeleted(null, null));
        m.put(TYPE_MEMORIES, memoryRepository.countDeleted(null, null));
        m.put(TYPE_PHOTOS, mealPhotoRepository.countDeleted(null, null));
        m.put(TYPE_DISHES, dishNutritionRepository.countDeleted(null));
        return m;
    }

    /** 恢复：清除 deleted=1/deleted_at；打卡级联恢复其软删饮食行；写审计 */
    @Transactional
    public void restore(String type, Long id, Long operatorId) {
        switch (type) {
            case TYPE_CHECKINS -> {
                CookingCheckin c = checkinRepository.findDeletedById(id);
                if (c == null) throw new IllegalArgumentException("打卡记录不存在或未处于删除状态");
                checkinRepository.restoreById(id);
                dietRecordRepository.restoreByCheckinId(c.getUserId(), id);
                log.info("[recycle] 恢复打卡 id={} userId={} by={}", id, c.getUserId(), operatorId);
            }
            case TYPE_DIET -> {
                if (dietRecordRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("饮食记录不存在或未处于删除状态");
                }
                dietRecordRepository.restoreById(id);
            }
            case TYPE_PLANS -> {
                if (planRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("计划不存在或未处于删除状态");
                }
                planRepository.restoreById(id);
            }
            case TYPE_WORKS -> {
                if (workRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("作品不存在或未处于删除状态");
                }
                workRepository.restoreById(id); // 磁盘图片在软删时保留，恢复即可用
            }
            case TYPE_MEMORIES -> {
                if (memoryRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("记忆不存在或未处于删除状态");
                }
                // 唯一键 (user_id, mem_key) 被软删行占住，恢复不会与现存活行冲突
                memoryRepository.restoreById(id);
            }
            case TYPE_PHOTOS -> {
                MealPhoto p = mealPhotoRepository.findDeletedById(id);
                if (p == null) throw new IllegalArgumentException("拍照记录不存在或未处于删除状态");
                mealPhotoRepository.restoreById(id);
                // 级联恢复关联 diet（scan 删除级联的逆向）
                if (p.getDietRecordId() != null) {
                    dietRecordRepository.restoreById(p.getDietRecordId());
                }
                log.info("[recycle] 恢复拍照打卡 id={} userId={} by={}", id, p.getUserId(), operatorId);
            }
            case TYPE_DISHES -> {
                if (dishNutritionRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("菜品不存在或未处于删除状态");
                }
                dishNutritionRepository.restoreById(id); // 恢复后重新进入命中/搜索
            }
            default -> throw new IllegalArgumentException("未知回收站类型: " + type);
        }
        audit(type, id, "restore", operatorId, null);
    }

    /**
     * 彻底删除（事务）：归档 INSERT…SELECT 到 *_archive → 物理 DELETE 原表。
     * 打卡：同时物理清理其软删饮食行（防孤儿）；计划：物理删触发 cooking_plan_items FK CASCADE；
     * 作品：归档后删除磁盘图片再物理删。
     */
    @Transactional
    public void purge(String type, Long id, Long operatorId) {
        switch (type) {
            case TYPE_CHECKINS -> {
                CookingCheckin c = checkinRepository.findDeletedById(id);
                if (c == null) throw new IllegalArgumentException("打卡记录不存在或未处于删除状态");
                checkinRepository.archiveRow(id, operatorId);
                dietRecordRepository.purgeByCheckinId(id);
                checkinRepository.purgeById(id);
                log.info("[recycle] 彻底删除打卡 id={} userId={} by={}", id, c.getUserId(), operatorId);
            }
            case TYPE_DIET -> {
                if (dietRecordRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("饮食记录不存在或未处于删除状态");
                }
                dietRecordRepository.archiveRow(id, operatorId);
                dietRecordRepository.purgeById(id);
            }
            case TYPE_PLANS -> {
                if (planRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("计划不存在或未处于删除状态");
                }
                planRepository.archiveRow(id, operatorId);
                planRepository.purgeById(id);
            }
            case TYPE_WORKS -> {
                if (workRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("作品不存在或未处于删除状态");
                }
                workRepository.archiveRow(id, operatorId);
                fileStorageService.deleteWorkImages(id); // 归档行保留 images 路径，磁盘图一并删
                workRepository.purgeById(id);
            }
            case TYPE_MEMORIES -> {
                if (memoryRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("记忆不存在或未处于删除状态");
                }
                memoryRepository.archiveRow(id, operatorId);
                memoryRepository.purgeById(id);
            }
            case TYPE_PHOTOS -> {
                MealPhoto p = mealPhotoRepository.findDeletedById(id);
                if (p == null) throw new IllegalArgumentException("拍照记录不存在或未处于删除状态");
                mealPhotoRepository.archiveRow(id, operatorId);
                // 级联物理清理软删饮食行 + 删除照片文件
                dietRecordRepository.purgeByPhotoId(id);
                fileStorageService.deletePhotoImage(p.getPhotoPath());
                mealPhotoRepository.purgeById(id);
                log.info("[recycle] 彻底删除拍照打卡 id={} userId={} by={}", id, p.getUserId(), operatorId);
            }
            case TYPE_DISHES -> {
                if (dishNutritionRepository.findDeletedById(id) == null) {
                    throw new IllegalArgumentException("菜品不存在或未处于删除状态");
                }
                dishNutritionRepository.archiveRow(id, operatorId);
                dishNutritionRepository.purgeById(id);
            }
            default -> throw new IllegalArgumentException("未知回收站类型: " + type);
        }
        audit(type, id, "purge", operatorId, null);
    }

    // ---------------- P2：审计日志 ----------------

    /** 审计日志分页（type/action 可选筛选） */
    public Map<String, Object> listAudit(String type, String action, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String t = (type == null || type.isBlank()) ? null : type.trim();
        String a = (action == null || action.isBlank()) ? null : action.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", auditLogRepository.findPage(t, a, safePage * safeSize, safeSize));
        out.put("total", auditLogRepository.count(t, a));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    private void audit(String type, Long id, String action, Long operatorId, String detail) {
        try {
            auditLogRepository.insertLog(operatorId, action, type, id, detail);
        } catch (Exception e) {
            log.warn("[recycle] 审计日志写入失败 type={} id={} action={}: {}", type, id, action, e.getMessage());
        }
    }
}
