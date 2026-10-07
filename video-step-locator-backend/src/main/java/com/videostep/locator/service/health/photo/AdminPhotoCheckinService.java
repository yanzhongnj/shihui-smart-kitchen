package com.videostep.locator.service.health.photo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.MealPhoto;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.repository.MealPhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拍照打卡 Admin 管理（Web 管理端）。设计文档 v0.2 §7.2 / §8。
 * 软删进回收站（TYPE_PHOTOS，级联软删关联 diet）；恢复走回收站 /api/admin/recycle/{type}/{id}/restore。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminPhotoCheckinService {

    private final MealPhotoRepository mealPhotoRepository;
    private final DietRecordRepository dietRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Admin 分页列表（含 username；返回菜品摘要 + 合计热量 + 关联 dietRecordId + status） */
    public Map<String, Object> list(String keyword, String dateFrom, String dateTo,
                                    String mealType, String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = safePage * safeSize;
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        String mt = (mealType == null || mealType.isBlank()) ? null : mealType.trim();
        String st = (status == null || status.isBlank()) ? null : status.trim();
        LocalDate from = parseDate(dateFrom);
        LocalDate to = parseDate(dateTo);
        List<Map<String, Object>> rows = mealPhotoRepository.findAdminPage(kw, from, to, mt, st, offset, safeSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            items.add(toListItem(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", mealPhotoRepository.countAdmin(kw, from, to, mt, st));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    /** Admin 详情（识别/份量/营养明细 + 照片路径 + 关联 diet） */
    public Map<String, Object> detail(Long id) {
        MealPhoto mp = mealPhotoRepository.findActiveById(id);
        if (mp == null) throw new IllegalArgumentException("拍照记录不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", mp.getId());
        out.put("userId", mp.getUserId());
        out.put("photoPath", mp.getPhotoPath());
        out.put("mealType", mp.getMealType());
        out.put("recordDate", mp.getRecordDate().toString());
        out.put("status", mp.getStatus());
        out.put("dietRecordId", mp.getDietRecordId());
        out.put("correctedAt", mp.getCorrectedAt());
        out.put("createdAt", mp.getCreatedAt());
        out.put("recognition", parseList(mp.getRecognitionJson()));
        out.put("portion", parseList(mp.getPortionJson()));
        double cal = 0;
        for (Map<String, Object> p : parseList(mp.getPortionJson())) {
            cal += num(p.get("calories"));
        }
        out.put("calories", Math.round(cal));
        return out;
    }

    /** Admin 统计 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", mealPhotoRepository.countAll());
        out.put("today", mealPhotoRepository.countToday());
        long confirmed = mealPhotoRepository.countConfirmed();
        long total = mealPhotoRepository.countAll();
        out.put("confirmed", confirmed);
        out.put("confirmedRate", total > 0 ? Math.round(confirmed * 100.0 / total) : 0);
        out.put("byStatus", mealPhotoRepository.countByStatus());
        out.put("byMealType", mealPhotoRepository.countByMealType());
        out.put("topUsers", mealPhotoRepository.topUsers(5));
        out.put("avgCalories", Math.round(mealPhotoRepository.avgCalories()));
        return out;
    }

    /** Admin 软删：软删拍照记录 + 级联软删关联 diet（对齐删打卡级联删 diet） */
    @Transactional
    public void delete(Long id) {
        MealPhoto mp = mealPhotoRepository.findActiveById(id);
        if (mp == null) throw new IllegalArgumentException("拍照记录不存在");
        mealPhotoRepository.softDeleteById(id);
        if (mp.getDietRecordId() != null) {
            try {
                dietRecordRepository.softDeleteById(mp.getDietRecordId());
            } catch (Exception e) {
                log.warn("admin photo delete cascade diet failed photo={}: {}", id, e.getMessage());
            }
        }
        log.info("[admin] 软删拍照打卡 id={} userId={}", id, mp.getUserId());
    }

    private Map<String, Object> toListItem(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("userId", r.get("user_id"));
        m.put("username", r.get("username"));
        m.put("photoPath", r.get("photo_path"));
        m.put("mealType", r.get("meal_type"));
        m.put("recordDate", r.get("record_date") == null ? null : String.valueOf(r.get("record_date")));
        m.put("status", r.get("status"));
        m.put("dietRecordId", r.get("diet_record_id"));
        m.put("createdAt", r.get("created_at"));
        // 菜品摘要
        List<String> names = new ArrayList<>();
        double cal = 0;
        for (Map<String, Object> p : parseList(String.valueOf(r.get("portion_json")))) {
            names.add(String.valueOf(p.get("dishName")));
            cal += num(p.get("calories"));
        }
        m.put("dishNames", names);
        m.put("dishSummary", String.join("、", names));
        m.put("calories", Math.round(cal));
        return m;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
