package com.videostep.locator.service.health.photo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.DietRecord;
import com.videostep.locator.entity.MealPhoto;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.repository.MealPhotoRepository;
import com.videostep.locator.service.FileStorageService;
import com.videostep.locator.service.GrowthService;
import com.videostep.locator.service.UsageService;
import com.videostep.locator.service.health.AiNutritionService;
import com.videostep.locator.service.health.CalorieLevelService;
import com.videostep.locator.service.health.DietRecordService;
import com.videostep.locator.service.health.HealthProfileService;
import com.videostep.locator.service.ingredient.FoodAliasService;
import com.videostep.locator.service.xunwei.XunweiVisionException;
import com.videostep.locator.service.xunwei.XunweiVisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拍照打卡编排（设计文档《拾味_拍照打卡_技术设计文档.md》v0.2 §6）。
 * 流程：限流 → 存照片 → recognizeDishes(多菜) → 归一 → 份量默认 → 每菜营养(AiNutritionService)
 *      → 存 meal_photos(pending) → 用量计量 → 返回预览。
 * 确认/改选：写 diet_records(source=scan, photo_id, estimated=1) + EXP+5（仅首次创建）+ 更新 meal_photos。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoCheckinService {

    private final XunweiVisionService visionService;
    private final AiNutritionService aiNutritionService;
    private final DishNutritionService dishNutritionService;
    private final FoodAliasService foodAliasService;
    private final MealPhotoRepository mealPhotoRepository;
    private final DietRecordRepository dietRecordRepository;
    private final DietRecordService dietRecordService;
    private final HealthProfileService healthProfileService;
    private final CalorieLevelService calorieLevelService;
    private final GrowthService growthService;
    private final UsageService usageService;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${health.checkin-photo.default-serving-grams:300}")
    private int defaultServingGrams;

    @Value("${health.checkin-photo.exp-gained:5}")
    private int expGained;

    @Value("${health.checkin-photo.rate-limit.daily-per-user:30}")
    private int dailyLimit;

    /** 视觉限流：内存日计数（复刻 HealthReviewService.checkRateLimit） */
    private final Map<Long, long[]> rateBucket = new ConcurrentHashMap<>();

    // ==================== 拍照 → 预览 ====================

    public Map<String, Object> checkin(Long userId, MultipartFile file, String mealType, String recordDate) {
        checkRateLimit(userId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要识别的照片");
        }
        // 1) 存照片
        String photoPath;
        try {
            photoPath = fileStorageService.savePhotoImage(userId, file.getInputStream(), file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("save photo failed userId={}: {}", userId, e.getMessage());
            throw new IllegalArgumentException("照片保存失败，请重试");
        }
        try {
            // 2) 识别多菜
            List<Map<String, Object>> dishes;
            try {
                dishes = visionService.recognizeDishes(file.getBytes(), file.getOriginalFilename());
            } catch (XunweiVisionException e) {
                throw e;
            }
            if (dishes.isEmpty()) {
                throw new IllegalArgumentException("未从图片中识别到菜品，请换一张更清晰的照片重试");
            }
            // 3) 归一菜名（重建可变 Map，避免修改不可变 Map.of 结果）
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Map<String, Object> d : dishes) {
                String name = foodAliasService.normalize(String.valueOf(d.getOrDefault("dishName", "")));
                if (name.isBlank()) continue;
                Map<String, Object> nd = new LinkedHashMap<>();
                nd.put("dishName", name);
                nd.put("confidence", d.get("confidence"));
                nd.put("source", "vision");
                normalized.add(nd);
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("未从图片中识别到有效菜品，请重试");
            }
            dishes = normalized;
            // 4) 份量默认 + 每菜营养估算
            List<Map<String, Object>> portion = new ArrayList<>();
            for (Map<String, Object> d : dishes) {
                String dishName = String.valueOf(d.get("dishName"));
                if (dishName.isBlank()) continue;
                Map<String, Object> nut = estimateDish(dishName, defaultServingGrams);
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("dishName", dishName);
                p.put("portionGrams", defaultServingGrams);
                p.put("method", "default");
                p.put("calories", nut.get("calories"));
                p.put("protein", nut.get("protein"));
                p.put("fat", nut.get("fat"));
                p.put("carbs", nut.get("carbs"));
                p.put("estimated", true);
                portion.add(p);
            }
            if (portion.isEmpty()) {
                throw new IllegalArgumentException("未从图片中识别到有效菜品，请重试");
            }
            // 5) 存 meal_photos(pending)
            String mt = (mealType == null || mealType.isBlank()) ? "dinner" : mealType;
            LocalDate date = parseDate(recordDate);
            MealPhoto mp = MealPhoto.builder()
                    .userId(userId)
                    .photoPath(photoPath)
                    .mealType(mt)
                    .recordDate(date)
                    .recognitionJson(toJson(dishes))
                    .portionJson(toJson(portion))
                    .status("pending")
                    .build();
            mealPhotoRepository.insert(mp);
            // 6) 用量计量（管理员可见 /api/admin/usage）
            try {
                usageService.record(userId, null, UsageService.TYPE_PHOTO_CHECKIN, mp.getId(), file.getSize());
            } catch (Exception e) {
                log.warn("usage record photo_checkin failed photo={}: {}", mp.getId(), e.getMessage());
            }
            return buildPreview(mp);
        } catch (Exception e) {
            // 失败时清理已存照片，避免孤儿文件
            fileStorageService.deletePhotoImage(photoPath);
            if (e instanceof IllegalArgumentException iae) throw iae;
            if (e instanceof XunweiVisionException ve) throw new IllegalArgumentException(ve.getMessage());
            log.warn("photo checkin failed userId={}: {}", userId, e.getMessage(), e);
            throw new IllegalArgumentException("拍照打卡失败，请稍后重试");
        }
    }

    /** 重取预览（断线重试/刷新） */
    public Map<String, Object> preview(Long userId, Long photoId) {
        MealPhoto mp = mealPhotoRepository.findActiveById(photoId);
        if (mp == null || !mp.getUserId().equals(userId)) {
            throw new IllegalArgumentException("拍照记录不存在或无权访问");
        }
        return buildPreview(mp);
    }

    // ==================== 确认 / 改选 ====================

    public Map<String, Object> confirm(Long userId, Long photoId, List<Map<String, Object>> items, String voiceText) {
        return finalizeCheckin(userId, photoId, items, false, voiceText);
    }

    public Map<String, Object> revise(Long userId, Long photoId, List<Map<String, Object>> items, String voiceText) {
        return finalizeCheckin(userId, photoId, items, true, voiceText);
    }

    /** 确认/改选：写 diet_records(source=scan) + EXP（仅首次创建）+ 更新 meal_photos + 反馈回写 */
    @Transactional
    public Map<String, Object> finalizeCheckin(Long userId, Long photoId,
                                               List<Map<String, Object>> items, boolean corrected, String voiceText) {
        MealPhoto mp = mealPhotoRepository.findActiveById(photoId);
        if (mp == null || !mp.getUserId().equals(userId)) {
            throw new IllegalArgumentException("拍照记录不存在或无权操作");
        }
        if ((items == null || items.isEmpty()) && voiceText != null && !voiceText.isBlank()) {
            // P1 语音补充：只说菜名 → 按默认份量加一道菜
            items = List.of(Map.of("dishName", voiceText.trim(), "portionGrams", defaultServingGrams));
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("请至少确认一道菜");
        }
        // 已存份量（用于份量变更时按比例缩放营养，避免重复 AI 调用）
        List<Map<String, Object>> stored = parseList(mp.getPortionJson());
        Map<String, Map<String, Object>> storedByName = new HashMap<>();
        for (Map<String, Object> p : stored) {
            storedByName.put(String.valueOf(p.get("dishName")), p);
        }

        List<Map<String, Object>> newPortion = new ArrayList<>();
        List<Map<String, Object>> dietItems = new ArrayList<>();
        double cal = 0, protein = 0, fat = 0, carbs = 0;
        for (Map<String, Object> it : items) {
            String dishName = foodAliasService.normalize(String.valueOf(it.getOrDefault("dishName", "")));
            if (dishName.isBlank()) continue;
            int grams = (int) Math.round(num(it.getOrDefault("portionGrams", defaultServingGrams)));
            grams = Math.max(50, Math.min(1000, grams));
            Map<String, Object> nut = scaleNutrition(dishName, storedByName.get(dishName), grams);
            newPortion.add(Map.of(
                    "dishName", dishName,
                    "portionGrams", grams,
                    "method", storedByName.containsKey(dishName) ? "manual" : "default",
                    "calories", nut.get("calories"),
                    "protein", nut.get("protein"),
                    "fat", nut.get("fat"),
                    "carbs", nut.get("carbs"),
                    "estimated", true));
            Map<String, Object> di = new LinkedHashMap<>();
            di.put("name", dishName);
            di.put("amount", grams);
            di.put("calories", nut.get("calories"));
            di.put("protein", nut.get("protein"));
            di.put("fat", nut.get("fat"));
            di.put("carbs", nut.get("carbs"));
            dietItems.add(di);
            cal += num(nut.get("calories"));
            protein += num(nut.get("protein"));
            fat += num(nut.get("fat"));
            carbs += num(nut.get("carbs"));
        }
        if (dietItems.isEmpty()) {
            throw new IllegalArgumentException("请至少确认一道菜");
        }

        // P1 反馈回写：命中菜品 hit_count+1（越拍越准）；AI 兜底确认 → 收录候选（source=ai, is_verified=0 待 Admin 审核）
        for (Map<String, Object> di : dietItems) {
            String dn = String.valueOf(di.get("name"));
            try {
                if (dishNutritionService.findByDishName(dn) != null) {
                    dishNutritionService.incrementHit(dn);
                } else {
                    Map<String, Object> nut = new LinkedHashMap<>();
                    nut.put("calories", di.get("calories"));
                    nut.put("protein", di.get("protein"));
                    nut.put("fat", di.get("fat"));
                    nut.put("carbs", di.get("carbs"));
                    dishNutritionService.recordAiCandidate(dn, nut, (int) Math.round(num(di.get("amount"))));
                }
            } catch (Exception e) {
                log.debug("photo checkin feedback writeback failed {}: {}", dn, e.getMessage());
            }
        }

        DietRecord diet = dietRecordRepository.findByPhotoId(photoId);
        boolean created = diet == null;
        String itemsJson = toJson(dietItems);
        if (created) {
            diet = DietRecord.builder()
                    .userId(userId)
                    .recordDate(mp.getRecordDate())
                    .mealType(mp.getMealType())
                    .items(itemsJson)
                    .photoId(photoId)
                    .calories(BigDecimal.valueOf(cal))
                    .protein(BigDecimal.valueOf(protein))
                    .fat(BigDecimal.valueOf(fat))
                    .carbs(BigDecimal.valueOf(carbs))
                    .portionFactor(BigDecimal.ONE)
                    .estimated(1)
                    .source("scan")
                    .build();
            dietRecordRepository.insert(diet);
        } else {
            diet.setItems(itemsJson);
            diet.setCalories(BigDecimal.valueOf(cal));
            diet.setProtein(BigDecimal.valueOf(protein));
            diet.setFat(BigDecimal.valueOf(fat));
            diet.setCarbs(BigDecimal.valueOf(carbs));
            dietRecordRepository.updateById(diet);
        }

        // 更新 meal_photos
        MealPhoto update = new MealPhoto();
        update.setId(photoId);
        update.setPortionJson(toJson(newPortion));
        update.setStatus(corrected ? "corrected" : "confirmed");
        update.setDietRecordId(diet.getId());
        if (corrected) update.setCorrectedAt(LocalDateTime.now());
        mealPhotoRepository.updateById(update);

        // EXP 仅首次创建时发放
        if (created) {
            try {
                growthService.addExp(userId, expGained, "photo_checkin", photoId);
            } catch (Exception e) {
                log.warn("photo_checkin exp failed photo={}: {}", photoId, e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("photoId", photoId);
        out.put("dietRecordId", diet.getId());
        out.put("expGained", created ? expGained : 0);
        out.put("corrected", corrected);
        out.put("status", corrected ? "corrected" : "confirmed");
        try {
            out.put("today", dietRecordService.today(userId));
        } catch (Exception e) {
            log.warn("today summary failed photo={}: {}", photoId, e.getMessage());
        }
        return out;
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> buildPreview(MealPhoto mp) {
        List<Map<String, Object>> recognition = parseList(mp.getRecognitionJson());
        List<Map<String, Object>> portion = parseList(mp.getPortionJson());
        double cal = 0, protein = 0, fat = 0, carbs = 0;
        for (Map<String, Object> p : portion) {
            cal += num(p.get("calories"));
            protein += num(p.get("protein"));
            fat += num(p.get("fat"));
            carbs += num(p.get("carbs"));
        }
        List<String> candidates = new ArrayList<>();
        for (Map<String, Object> r : recognition) {
            String n = String.valueOf(r.get("dishName"));
            if (!n.isBlank() && !candidates.contains(n)) candidates.add(n);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("photoId", mp.getId());
        out.put("photoPath", mp.getPhotoPath());
        out.put("mealType", mp.getMealType());
        out.put("recordDate", mp.getRecordDate().toString());
        out.put("status", mp.getStatus());
        out.put("dishes", recognition);
        out.put("portion", portion);
        out.put("candidates", candidates);
        out.put("calories", Math.round(cal));
        out.put("protein", round1(protein));
        out.put("fat", round1(fat));
        out.put("carbs", round1(carbs));
        out.put("estimated", true);
        out.put("calorieLevel", calorieLevelService.levelInfo(cal));
        Map<String, Object> targets = healthProfileService.targets(mp.getUserId());
        out.put("targets", targets);
        double tCal = num(targets.get("calories"));
        out.put("ratio", tCal > 0 ? Math.min(200, Math.round(cal * 100 / tCal)) : 0);
        return out;
    }

    /** 整道菜营养估算：菜品库命中优先（每份 × 份量系数），未命中 AI 兜底；失败降级全 0 */
    private Map<String, Object> estimateDish(String dishName, int grams) {
        Map<String, Object> db = dishNutritionService.nutritionOf(dishName);
        if (db != null) {
            int serving = defaultServingGrams;
            try {
                var de = dishNutritionService.findByDishName(dishName);
                if (de != null && de.getServingGrams() != null && de.getServingGrams() > 0) {
                    serving = de.getServingGrams();
                }
            } catch (Exception ignored) {
            }
            double ratio = grams / (double) serving;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("calories", Math.round(num(db.get("calories")) * ratio));
            out.put("protein", round1(num(db.get("protein")) * ratio));
            out.put("fat", round1(num(db.get("fat")) * ratio));
            out.put("carbs", round1(num(db.get("carbs")) * ratio));
            out.put("estimated", false);
            out.put("fromDb", true);
            return out;
        }
        try {
            Map<String, Object> ai = aiNutritionService.estimateByDescription(
                    dishName + "，标准一份约 " + grams + " 克");
            ai.put("estimated", true);
            ai.put("fromDb", false);
            return ai;
        } catch (Exception e) {
            log.warn("photo checkin ai estimate failed dish={}: {}", dishName, e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("calories", 0L);
            fallback.put("protein", 0.0);
            fallback.put("fat", 0.0);
            fallback.put("carbs", 0.0);
            fallback.put("estimated", true);
            fallback.put("fromDb", false);
            return fallback;
        }
    }

    /** 份量变更时按比例缩放营养；无历史（用户换入新菜）则走 AI 估算 */
    private Map<String, Object> scaleNutrition(String dishName, Map<String, Object> stored, int grams) {
        if (stored == null) return estimateDish(dishName, grams);
        int baseGrams = (int) Math.round(num(stored.getOrDefault("portionGrams", defaultServingGrams)));
        double ratio = baseGrams > 0 ? grams / (double) baseGrams : 1.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calories", Math.round(num(stored.get("calories")) * ratio));
        out.put("protein", round1(num(stored.get("protein")) * ratio));
        out.put("fat", round1(num(stored.get("fat")) * ratio));
        out.put("carbs", round1(num(stored.get("carbs")) * ratio));
        out.put("estimated", true);
        return out;
    }

    private void checkRateLimit(Long userId) {
        long today = LocalDate.now().toEpochDay();
        long[] b = rateBucket.computeIfAbsent(userId, k -> new long[]{today, 0});
        synchronized (b) {
            if (b[0] != today) {
                b[0] = today;
                b[1] = 0;
            }
            b[1]++;
            if (b[1] > dailyLimit) {
                throw new IllegalArgumentException("今日拍照打卡次数已达上限（" + dailyLimit + " 次），明天再来吧");
            }
        }
    }

    private LocalDate parseDate(String recordDate) {
        if (recordDate == null || recordDate.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(recordDate);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
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

    private String toJson(List<Map<String, Object>> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
