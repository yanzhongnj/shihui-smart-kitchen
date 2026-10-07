package com.videostep.locator.service.plan;

import com.videostep.locator.entity.CookingCheckin;
import com.videostep.locator.entity.CookingPlan;
import com.videostep.locator.entity.CookingPlanItem;
import com.videostep.locator.entity.DietRecord;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.CookingCheckinRepository;
import com.videostep.locator.repository.CookingPlanItemRepository;
import com.videostep.locator.repository.CookingPlanRepository;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.health.DietRecordService;
import com.videostep.locator.service.xunwei.VideoCandidate;
import com.videostep.locator.service.xunwei.XunweiSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划执行服务（做饭计划执行闭环 P0）。
 *  - 线路 B：条目「跟做」视频带出（v0.3 决策 7.7-5）——有 videoId 直进；无则后端直调
 *    XunweiSearchService 取首个候选视频；失败由 APP 跳寻味带 initialPrompt。
 *  - 打卡联动（v0.3 决策 7.7-4）：done → 自动写 cooking_checkins(source=plan，条目幂等)
 *    + diet_records(source=plan，跨来源去重 user+date+recipe_id，防热量重复)。
 *  - 份量：完成前可调（0.5/1.0/1.5），打卡后锁定。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanExecutionService {

    private final CookingPlanRepository planRepository;
    private final CookingPlanItemRepository itemRepository;
    private final RecipeMetadataRepository recipeRepository;
    private final CookingCheckinRepository checkinRepository;
    private final DietRecordRepository dietRecordRepository;
    private final DietRecordService dietRecordService;
    private final XunweiSearchService xunweiSearchService;

    @Value("${plan.plan-items.portion-options:0.5,1.0,1.5}")
    private String portionOptionsCsv;

    // ================= 线路 B：视频带出 =================

    /** 按 id 取菜谱（供 PlanService 创建条目时带出 videoId） */
    public RecipeMetadata findRecipe(Long recipeId) {
        if (recipeId == null) return null;
        return recipeRepository.selectById(recipeId);
    }

    public Map<String, Object> attachVideo(Long userId, Long itemId) {
        CookingPlanItem item = requireOwnedItem(userId, itemId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", itemId);
        out.put("recipeTitle", item.getRecipeTitle());

        if (item.getVideoId() != null) {
            out.put("found", true);
            out.put("mode", "local");
            out.put("videoId", item.getVideoId());
            out.put("recipeId", item.getRecipeId());
            return out;
        }

        String dish = item.getRecipeTitle() == null || item.getRecipeTitle().isBlank()
                ? "家常菜" : item.getRecipeTitle();
        if ("家常菜".equals(dish)) {
            out.put("found", false);
            out.put("message", "该条目未关联菜名，无法自动带出视频");
            return out;
        }

        try {
            List<Map<String, Object>> queries = new ArrayList<>();
            queries.add(Map.of("keyword", dish, "platform", "auto", "count", 5));
            List<VideoCandidate> candidates = xunweiSearchService.search(queries);
            if (!candidates.isEmpty()) {
                VideoCandidate c = candidates.get(0);
                out.put("found", true);
                out.put("mode", "external");
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", c.id());
                v.put("url", c.url());
                v.put("title", c.title());
                v.put("uploader", c.uploader());
                v.put("duration", c.duration());
                v.put("viewCount", c.viewCount());
                v.put("thumbnail", c.thumbnail());
                v.put("platform", c.platform());
                out.put("video", v);
                return out;
            }
        } catch (Exception e) {
            log.warn("[计划] 线路B 搜索失败 item={}: {}", itemId, e.getMessage());
        }
        out.put("found", false);
        out.put("message", "未搜索到「" + dish + "」的参考视频，可去寻味对话获取");
        return out;
    }

    // ================= 打卡联动（done 时调用） =================

    /**
     * 计划条目完成 → 自动写打卡(source=plan) + 饮食记录(source=plan，去重)。
     * 幂等：条目已有关联 checkin_id 则不再重复写。
     * 注意：本方法仅修改传入 item 对象的字段（不 updateById），由调用方统一持久化。
     */
    public Map<String, Object> autoCheckinOnDone(Long userId, CookingPlanItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("autoCheckin", true);

        // 幂等
        if (item.getCheckinId() != null) {
            out.put("checkinId", item.getCheckinId());
            out.put("dietRecordId", item.getDietRecordId());
            out.put("deduped", true);
            out.put("alreadyDone", true);
            return out;
        }

        RecipeMetadata recipe = item.getRecipeId() != null
                ? recipeRepository.selectById(item.getRecipeId()) : null;

        // 打卡（source=plan；EXP 由 PlanService 统一加，这里不再叠加）
        CookingCheckin c = CookingCheckin.builder()
                .userId(userId)
                .checkinDate(LocalDate.now())
                .videoId(item.getVideoId())
                .recipeTitle(item.getRecipeTitle() == null || item.getRecipeTitle().isBlank()
                        ? "家常菜" : item.getRecipeTitle())
                .mealType(item.getMealType() == null || item.getMealType().isBlank()
                        ? "dinner" : item.getMealType())
                .mood("normal")
                .calories(recipe != null ? recipe.getCalories() : null)
                .source("plan")
                .build();
        checkinRepository.insert(c);
        item.setCheckinId(c.getId());
        out.put("checkinId", c.getId());

        // 饮食记录（source=plan）+ 跨来源去重（user + date + recipe_id）
        double scale = item.getPortionScale() != null ? item.getPortionScale().doubleValue() : 1.0;
        if (recipe != null) {
            Long existing = dietRecordRepository.findIdByUserDateRecipe(
                    userId, LocalDate.now(), recipe.getId());
            if (existing != null) {
                item.setDietRecordId(existing);
                out.put("dietRecordId", existing);
                out.put("deduped", true);
            } else {
                try {
                    DietRecord d = dietRecordService.createFromCheckin(
                            userId, c.getId(), recipe, item.getMealType(), scale, "plan");
                    if (d != null) {
                        item.setDietRecordId(d.getId());
                        out.put("dietRecordId", d.getId());
                        out.put("dietEstimated", d.getEstimated());
                    }
                    out.put("deduped", false);
                } catch (Exception e) {
                    log.warn("[计划] 自动饮食记录失败 item={}: {}", item.getId(), e.getMessage());
                    out.put("deduped", false);
                    out.put("dietError", e.getMessage());
                }
            }
        } else {
            out.put("deduped", false);
            out.put("noRecipe", true);
        }

        // 份量锁定（v0.2 决策 7.5 / v0.3）
        item.setPortionLocked(1);
        return out;
    }

    // ================= 份量 =================

    public Map<String, Object> setPortion(Long userId, Long itemId, double scale) {
        CookingPlanItem item = requireOwnedItem(userId, itemId);
        if (item.getPortionLocked() != null && item.getPortionLocked() == 1) {
            throw new IllegalArgumentException("该条目已完成打卡，份量已锁定，无法调整");
        }
        double s = Math.round(scale * 10.0) / 10.0;
        if (!allowedPortion(s)) {
            throw new IllegalArgumentException("份量档位仅支持 " + portionOptionsCsv + "（如 1.0 表示正常份量）");
        }
        item.setPortionScale(new java.math.BigDecimal(String.valueOf(s)));
        itemRepository.updateById(item);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", itemId);
        out.put("portionScale", item.getPortionScale());
        out.put("portionLocked", item.getPortionLocked());
        return out;
    }

    // ================= 内部 =================

    private CookingPlanItem requireOwnedItem(Long userId, Long itemId) {
        CookingPlanItem item = itemRepository.selectById(itemId);
        if (item == null) throw new IllegalArgumentException("计划条目不存在");
        CookingPlan plan = planRepository.selectById(item.getPlanId());
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该计划条目");
        }
        return item;
    }

    private boolean allowedPortion(double s) {
        for (String t : portionOptionsCsv.split(",")) {
            try {
                if (Double.parseDouble(t.trim()) == s) return true;
            } catch (Exception ignored) {
            }
        }
        return s == 0.5 || s == 1.0 || s == 1.5;
    }
}
