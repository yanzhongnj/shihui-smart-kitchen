package com.videostep.locator.service;

import com.videostep.locator.entity.CookingPlan;
import com.videostep.locator.entity.CookingPlanItem;
import com.videostep.locator.repository.CookingPlanItemRepository;
import com.videostep.locator.repository.CookingPlanRepository;
import com.videostep.locator.service.plan.PlanExecutionService;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 做饭计划（Keep 式训练计划）：创建每周计划 + 列表 + 删除 + 条目完成标记。
 * 完成一条计划 → EXP+15 → 徽章校验 → 寻味记忆（跟做过的菜）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanService {

    private final CookingPlanRepository planRepository;
    private final CookingPlanItemRepository itemRepository;
    private final GrowthService growthService;
    private final XunweiMemoryService xunweiMemoryService;
    private final PlanExecutionService planExecutionService;

    public static LocalDate mondayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1);
    }

    public Map<String, Object> create(Long userId, String title, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("请至少添加一道菜到计划");
        }
        if (title == null || title.isBlank()) title = "本周计划";
        CookingPlan plan = CookingPlan.builder()
                .userId(userId)
                .title(title.trim())
                .planType("weekly")
                .startDate(mondayOf(LocalDate.now()))
                .build();
        planRepository.insert(plan);

        int dayOffset = 0;
        for (Map<String, Object> it : items) {
            if (it.get("dayOffset") instanceof Number n) dayOffset = n.intValue();
            Long videoId = it.get("videoId") instanceof Number n ? n.longValue() : null;
            Long recipeId = it.get("recipeId") instanceof Number n ? n.longValue() : null;
            String recipeTitle = it.get("recipeTitle") instanceof String s ? s : null;
            String mealType = it.get("mealType") instanceof String s ? s : null;
            if (recipeTitle == null || recipeTitle.isBlank()) recipeTitle = "家常菜";
            if (mealType == null || mealType.isBlank()) mealType = "dinner";
            // 传入 recipeId 时自动带出菜谱的 videoId（线路 B 直进；手工无 recipe 条目保持 null）
            if (videoId == null && recipeId != null) {
                var recipe = planExecutionService.findRecipe(recipeId);
                if (recipe != null) videoId = recipe.getVideoId();
            }
            itemRepository.insert(CookingPlanItem.builder()
                    .planId(plan.getId())
                    .dayOffset(dayOffset)
                    .videoId(videoId)
                    .recipeId(recipeId)
                    .recipeTitle(recipeTitle.trim())
                    .mealType(mealType)
                    .portionScale(new BigDecimal("1.00"))
                    .portionLocked(0)
                    .done(0)
                    .build());
        }
        return toMap(plan);
    }

    public List<Map<String, Object>> list(Long userId) {
        return planRepository.findByUserId(userId).stream()
                .map(this::toMap).collect(Collectors.toList());
    }

    public void delete(Long userId, Long planId) {
        CookingPlan plan = planRepository.selectById(planId);
        if (plan == null) throw new IllegalArgumentException("计划不存在");
        if (!plan.getUserId().equals(userId)) throw new IllegalArgumentException("无权删除该计划");
        planRepository.softDeleteById(planId); // 软删 + 记录 deleted_at（条目 FK CASCADE 仅物理删触发）
    }

    /** 删除单条计划条目（v0.5 反馈增强：每项菜单可删除）；删空则连带删除计划（v0.8 反馈） */
    public Map<String, Object> deleteItem(Long userId, Long itemId) {
        CookingPlanItem item = requireOwnedItem(userId, itemId);
        Long planId = item.getPlanId();
        itemRepository.deleteById(itemId); // cooking_plan_items 无软删列，物理删
        boolean planDeleted = false;
        if (itemRepository.findByPlanId(planId).isEmpty()) {
            // 条目删空 → 连带软删计划（避免残留空计划，v0.8）
            planRepository.softDeleteById(planId);
            planDeleted = true;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("deleted", true);
        m.put("planDeleted", planDeleted);
        return m;
    }

    /** 调整条目餐次/星期（v0.5 反馈增强：调整到不同时间的餐次） */
    public Map<String, Object> updateItem(Long userId, Long itemId, Integer dayOffset, String mealType) {
        CookingPlanItem item = requireOwnedItem(userId, itemId);
        if (dayOffset != null && dayOffset >= 0 && dayOffset <= 6) item.setDayOffset(dayOffset);
        if (mealType != null && !mealType.isBlank()) item.setMealType(mealType.trim());
        itemRepository.updateById(item);
        return itemMap(item);
    }

    private CookingPlanItem requireOwnedItem(Long userId, Long itemId) {
        CookingPlanItem item = itemRepository.selectById(itemId);
        if (item == null) throw new IllegalArgumentException("计划条目不存在");
        CookingPlan plan = planRepository.selectById(item.getPlanId());
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该计划条目");
        }
        return item;
    }

    /** 标记完成/取消；完成 → EXP+15 → 徽章 → 寻味记忆 → 自动打卡+饮食记录（v0.3 决策 7.7-4） */
    public Map<String, Object> markDone(Long userId, Long itemId, boolean done) {
        CookingPlanItem item = itemRepository.selectById(itemId);
        if (item == null) throw new IllegalArgumentException("计划条目不存在");
        CookingPlan plan = planRepository.selectById(item.getPlanId());
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该计划");
        }
        item.setDone(done ? 1 : 0);
        if (done) {
            growthService.addExp(userId, 15, "plan", item.getId());
            String title = item.getRecipeTitle();
            if (title != null && !title.isBlank() && !"家常菜".equals(title)) {
                xunweiMemoryService.ingestBehavior(userId, "plan", title, true);
            }
            // 打卡联动：source=plan + 跨来源去重 + 份量锁定（item 字段由下方统一持久化）
            Map<String, Object> exec = planExecutionService.autoCheckinOnDone(userId, item);
            itemRepository.updateById(item);
            Map<String, Object> result = new HashMap<>();
            result.put("itemId", item.getId());
            result.put("done", true);
            result.put("newBadges", growthService.checkBadges(userId));
            result.put("exec", exec);
            return result;
        }
        itemRepository.updateById(item);
        Map<String, Object> result = new HashMap<>();
        result.put("itemId", item.getId());
        result.put("done", false);
        result.put("newBadges", growthService.checkBadges(userId));
        return result;
    }

    private Map<String, Object> toMap(CookingPlan p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("title", p.getTitle());
        m.put("planType", p.getPlanType());
        m.put("startDate", p.getStartDate().toString());
        m.put("createdAt", p.getCreatedAt());
        m.put("menuId", p.getMenuId());
        m.put("generatedBy", p.getGeneratedBy());
        List<CookingPlanItem> items = itemRepository.findByPlanId(p.getId());
        m.put("items", items.stream().map(this::itemMap).collect(Collectors.toList()));
        m.put("totalItems", items.size());
        m.put("doneItems", itemRepository.countDone(p.getId()));
        return m;
    }

    private Map<String, Object> itemMap(CookingPlanItem it) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", it.getId());
        m.put("dayOffset", it.getDayOffset());
        m.put("videoId", it.getVideoId());
        m.put("recipeId", it.getRecipeId());
        m.put("recipeTitle", it.getRecipeTitle());
        m.put("mealType", it.getMealType());
        m.put("dishType", it.getDishType());
        m.put("execMode", it.getExecMode());
        m.put("portionScale", it.getPortionScale() != null ? it.getPortionScale() : new BigDecimal("1.00"));
        m.put("portionLocked", it.getPortionLocked() != null && it.getPortionLocked() == 1);
        m.put("checkinId", it.getCheckinId());
        m.put("dietRecordId", it.getDietRecordId());
        m.put("done", it.getDone() != null && it.getDone() == 1);
        return m;
    }
}
