package com.videostep.locator.service.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.CookingMenu;
import com.videostep.locator.entity.CookingPlan;
import com.videostep.locator.entity.CookingPlanItem;
import com.videostep.locator.entity.NutritionFood;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.UserFavorite;
import com.videostep.locator.repository.CookingMenuRepository;
import com.videostep.locator.repository.CookingPlanItemRepository;
import com.videostep.locator.repository.CookingPlanRepository;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.UserFavoriteRepository;
import com.videostep.locator.service.NutritionCalculator;
import com.videostep.locator.service.PreferenceService;
import com.videostep.locator.service.health.FoodLibraryService;
import com.videostep.locator.service.health.HealthProfileService;
import com.videostep.locator.service.safety.SafetyCheckService;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 菜单生成服务（做饭计划执行闭环 P0，v0.3 决策 7.7-3）。
 *
 * 仿寻味双轮编排：
 *   Round1 规划（健康档案+画像 → 一周菜名清单）
 *   → 候选召回（现有 recipes 库，favorites 收藏/做过优先加权 / random 全库随机）
 *   → Round2 生成（候选喂回 LLM 挑选 → 结构化周菜单；只保留命中考生的条目，幻觉菜名排除）
 * 限流：每用户每日 N 次（plan.menu.generate-rate-limit.daily-per-user，默认 10）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MenuPlannerService {

    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CookingMenuRepository menuRepository;
    private final CookingPlanRepository planRepository;
    private final CookingPlanItemRepository itemRepository;
    private final RecipeMetadataRepository recipeRepository;
    private final RecipeIngredientRepository ingredientRepository;
    private final PreferenceService preferenceService;
    private final HealthProfileService healthProfileService;
    private final XunweiMemoryService xunweiMemoryService;
    private final UserFavoriteRepository favoriteRepository;
    private final SafetyCheckService safetyCheckService;
    private final NutritionCalculator nutritionCalculator;
    private final FoodLibraryService foodLibraryService;

    @Value("${plan.menu.generate-rate-limit.daily-per-user:10}")
    private int dailyRateLimit;

    @Value("${plan.menu.default-generate-mode:favorites}")
    private String defaultGenerateMode;

    @Value("${plan.menu.default-respect-taboos:true}")
    private boolean defaultRespectTaboos;

    @Value("${plan.menu.default-respect-medications:true}")
    private boolean defaultRespectMedications;

    /** 每用户每日限流计数：key = userId:epochDay */
    private final Map<String, Integer> rateCounts = new ConcurrentHashMap<>();

    private static final String ROUND1_SYSTEM_PROMPT = """
            你是「拾味」的营养膳食规划师。根据用户的健康档案与口味/忌口画像，为未来一周制定每日做菜计划。

            【要求】
            - 覆盖用户指定的餐次（如 仅晚餐 / 早餐+午餐 / 早餐+午餐+晚餐），每天每餐按用户要求的荤素搭配出菜。
            - 荤素搭配：荤=含肉/鱼/虾/蛋等主料；素=纯蔬菜/豆制品/菌菇/主食等。
            - 菜名必须是真实常见的中文家常菜名（如：鱼香肉丝、清蒸鲈鱼、番茄炒蛋）。
            - 菜式多样、避免重复；严格遵循用户的忌口/过敏/用药约束；热量作为软参考。
            - 只输出 JSON，不要输出任何其它文字或 Markdown。

            【输出格式】
            {"dishes":[{"name":"菜名","day_offset":0,"meal_type":"dinner","dish_type":"meat","reason":"一句理由"}]}
            - day_offset: 0=起始日，1=次日，依此类推（天数见用户约束）；meal_type: breakfast|lunch|dinner|snack；dish_type: meat(荤)|veg(素)
            """;

    /** 荤素兜底关键词（主料营养库分类未收录时用；避免单字鸡/鸭/鱼误判菌菇） */
    private static final String[] MEAT_KEYWORDS = {
            "猪肉", "牛肉", "羊肉", "鸡肉", "鸭肉", "鱼肉", "排骨", "里脊", "五花肉", "肉馅", "肉末",
            "猪排", "牛腩", "牛腱", "羊排", "鸡腿", "鸡翅", "鸡胸", "鸭腿", "鱼片", "鱼头", "虾仁", "蟹肉",
            "肉", "虾", "蟹", "蛋", "火腿", "培根", "腊肠", "香肠"
    };

    private static final String ROUND2_SYSTEM_PROMPT = """
            你是「拾味」的周菜单生成器。系统已根据你的规划召回了一批候选菜谱（含菜谱ID/菜名/热量/口味/菜系/主料）。

            【要求】
            - 从候选中为规划里的菜各挑选 1 道，生成最终周菜单。
            - 只能从候选列表中选择，严禁编造候选之外的菜谱，尤其不能编造 recipe_id。
            - 每道菜必须携带候选列表中的 recipe_id。
            - 只输出 JSON，不要输出任何其它文字或 Markdown。

            【输出格式】
            {"items":[{"day_offset":0,"meal_type":"dinner","dish_name":"菜名","recipe_id":123,"note":"一句备注"}]}
            """;

    // ================= 生成 =================

    /**
     * AI 生成菜单（预览，status=preview，未写入计划）。
     * req: {generateMode?, respectTaboos?, respectMedications?, week(起始日)?, days?, title?, maxCookTime?, cuisines[]?, pattern?, meals[]?}
     */
    public Map<String, Object> generate(Long userId, Map<String, Object> req) {
        int used = rateCounts.merge(rateKey(userId), 1, Integer::sum);
        if (used > dailyRateLimit) {
            throw new IllegalArgumentException("今日 AI 菜单生成次数已达上限（" + dailyRateLimit + " 次），请明天再试");
        }

        String mode = req.get("generateMode") instanceof String s && !s.isBlank()
                ? s : defaultGenerateMode;
        boolean respectTaboos = boolOf(req.get("respectTaboos"), defaultRespectTaboos);
        boolean respectMeds = boolOf(req.get("respectMedications"), defaultRespectMedications);
        // 起始日（默认今天）与天数（默认 7，1~14）；标题（确认写入计划）
        LocalDate startDate = parseStartDate(req.get("week"));
        int days = req.get("days") instanceof Number n ? Math.max(1, Math.min(14, n.intValue())) : 7;
        String title = req.get("title") instanceof String s && !s.isBlank()
                ? s.trim() : "AI 周菜单";
        int maxCookTime = req.get("maxCookTime") instanceof Number n ? n.intValue() : 0;
        // 荤素搭配模式：1meat1veg(一荤一素) | 1meat2veg(一荤两素) | 2meat2veg(两荤两素)
        String pattern = req.get("pattern") instanceof String s && !s.isBlank()
                ? s : "1meat1veg";
        // 覆盖餐次（默认仅晚餐；可选 breakfast/lunch/dinner）
        List<String> meals = parseMeals(req.get("meals"));

        // 1. 用户上下文（健康档案 + 画像 + 收藏/做过 + 搭配/餐次 + 天数/起始日）
        String context = buildUserContext(userId, mode, respectTaboos, respectMeds, maxCookTime, pattern, meals, startDate, days);

        // 2. Round1 规划
        List<Map<String, Object>> plan = doRound1(context);
        if (plan.isEmpty()) {
            throw new IllegalArgumentException("AI 暂时无法生成菜单，请稍后重试");
        }
        // 动态条目上限（天数×餐次×每餐道数）
        int planCap = Math.max(days, days * meals.size() * (patternMeat(pattern) + patternVeg(pattern)));
        if (plan.size() > planCap) {
            plan = new ArrayList<>(plan.subList(0, planCap));
        }

        // 3. 候选召回（按菜名在 recipes 库检索，favorites 加权 / random 随机）
        Map<String, List<RecipeMetadata>> candidatesByDish = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> d : plan) {
            String name = String.valueOf(d.get("name"));
            List<RecipeMetadata> cands = recallCandidates(userId, name, mode, 8);
            if (cands.isEmpty()) {
                missing.add(name);
            } else {
                candidatesByDish.put(name, cands);
            }
        }

        // 4. Round2 生成（候选喂回 LLM 挑选；失败降级=每道菜取最佳候选）
        List<Map<String, Object>> items = doRound2(userId, context, candidatesByDish, mode);
        if (items == null || items.isEmpty()) {
            items = fallbackPick(userId, candidatesByDish, mode);
        }

        // 5. 过滤：只保留命中考生的条目（幻觉排除）+ 口忌/用药拦截
        Set<Long> allowedIds = new HashSet<>();
        candidatesByDish.values().forEach(l -> l.forEach(r -> allowedIds.add(r.getId())));
        List<Map<String, Object>> kept = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        Set<Long> keptRecipeIds = new HashSet<>();
        for (Map<String, Object> it : items) {
            Object rid = it.get("recipe_id");
            if (!(rid instanceof Number)) continue;
            long recipeId = ((Number) rid).longValue();
            if (!allowedIds.contains(recipeId)) {
                excluded.add(safeItem(it, "幻觉菜名已排除（不在候选池）"));
                continue;
            }
            // 同菜谱去重（跨天/餐不拦，允许多餐多道）
            if (!keptRecipeIds.add(recipeId)) {
                excluded.add(safeItem(it, "重复菜谱已去重"));
                continue;
            }
            int day = it.get("day_offset") instanceof Number n ? n.intValue() : 0;
            String meal = it.get("meal_type") instanceof String s && !s.isBlank() ? s : "dinner";

            RecipeMetadata r = recipeRepository.selectById(recipeId);
            if (r == null) continue;
            Map<String, Object> safe = safeItem(it, null);
            safe.put("recipe_id", recipeId);
            safe.put("dish_name", r.getTitle() != null ? r.getTitle() : safe.get("dish_name"));
            safe.put("day_offset", day);
            safe.put("meal_type", meal);
            // 荤/素标注（按主料分类判定，与补全口径一致）
            safe.put("dish_type", classifyMeatVeg(r, userId));

            // 口忌/过敏（PreferenceService RED 硬拦截）
            if (respectTaboos) {
                Map<String, Object> eval = preferenceService.evaluateMerged(userId, r);
                if ("RED".equals(eval.get("level"))) {
                    excluded.add(withReason(safe, "含忌口/过敏原：" + eval.get("reasons")));
                    continue;
                }
            }
            // 用药（食物安全知识库 MED 类 RED）
            if (respectMeds) {
                List<String> ings = extractIngredientNames(r);
                try {
                    List<Map<String, Object>> risks = safetyCheckService.checkForUser(userId, ings, null);
                    for (Map<String, Object> risk : risks) {
                        if ("MED".equals(risk.get("category")) && "RED".equals(risk.get("level"))) {
                            excluded.add(withReason(safe, "与用药冲突：" + risk.get("title")));
                            continue;
                        }
                    }
                } catch (Exception e) {
                    log.warn("菜单用药校验失败 recipe={}: {}", recipeId, e.getMessage());
                }
            }

            // 营养/口味标注
            safe.put("nutrition", computeNutrition(r));
            safe.put("taste_tags", r.getTasteTags());
            safe.put("device_type", r.getCuisine());
            kept.add(safe);
        }

        // 5b. 荤素搭配/餐次补全（v0.5 反馈修复）：确保每天每餐达到 pattern 的荤素数量
        //     填充池 = 已召回候选 + 库内可见菜谱抽样（保证荤素类型覆盖），按主料荤素判定补足缺失荤/素
        List<RecipeMetadata> pool = new ArrayList<>();
        Set<Long> poolIds = new HashSet<>();
        for (List<RecipeMetadata> l : candidatesByDish.values()) {
            for (RecipeMetadata r : l) {
                if (r != null && poolIds.add(r.getId())) pool.add(r);
            }
        }
        try {
            List<RecipeMetadata> broad = recipeRepository.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RecipeMetadata>()
                            .last("LIMIT 500"));
            for (RecipeMetadata r : broad) {
                if (r == null || !poolIds.add(r.getId())) continue;
                if (keptRecipeIds.contains(r.getId())) continue;
                if (!isCandidateVisible(r, userId)) continue;
                pool.add(r);
            }
        } catch (Exception e) {
            log.warn("[计划] 填充池抽样失败: {}", e.getMessage());
        }
        Set<Long> favIds = new HashSet<>();
        if ("favorites".equals(mode)) {
            for (UserFavorite f : favoriteRepository.findByUserId(userId)) {
                if (f.getRecipeId() != null) favIds.add(f.getRecipeId());
            }
        }
        pool.sort((a, b) -> Integer.compare(score(b, favIds, userId), score(a, favIds, userId)));
        Set<Long> usedFill = new HashSet<>();
        Map<String, List<Map<String, Object>>> slotMap = new LinkedHashMap<>();
        for (Map<String, Object> it : kept) {
            slotMap.computeIfAbsent(it.get("day_offset") + ":" + it.get("meal_type"), k -> new ArrayList<>()).add(it);
        }
        int slotFill = 0;
        for (int day = 0; day < days; day++) {
            for (String meal : meals) {
                String slot = day + ":" + meal;
                List<Map<String, Object>> cur = slotMap.getOrDefault(slot, new ArrayList<>());
                int meat = 0, veg = 0;
                for (Map<String, Object> it : cur) {
                    if ("meat".equals(it.get("dish_type"))) meat++;
                    else if ("veg".equals(it.get("dish_type"))) veg++;
                }
                int needMeat = patternMeat(pattern) - meat;
                int needVeg = patternVeg(pattern) - veg;
                for (int i = 0; i < Math.max(0, needMeat + needVeg); i++) {
                    boolean wantMeat = i < needMeat;
                    Map<String, Object> fill = pickFill(pool, usedFill, userId,
                            wantMeat ? "meat" : "veg", respectTaboos, respectMeds);
                    if (fill == null) break;
                    fill.put("day_offset", day);
                    fill.put("meal_type", meal);
                    fill.put("dish_type", wantMeat ? "meat" : "veg");
                    kept.add(fill);
                    slotMap.computeIfAbsent(slot, k -> new ArrayList<>()).add(fill);
                    slotFill++;
                }
            }
        }
        if (slotFill > 0) log.info("[计划] 荤素/餐次补全 userId={} 补入 {} 道", userId, slotFill);

        if (kept.isEmpty()) {
            throw new IllegalArgumentException("根据您的口忌/用药约束，暂无可选菜谱，请调整约束后重试");
        }

        // 6. 持久化预览
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("calories_target", healthProfileService.targets(userId).get("calories"));
        constraints.put("generate_mode", mode);
        constraints.put("respect_taboos", respectTaboos);
        constraints.put("respect_medications", respectMeds);
        constraints.put("max_cook_time", maxCookTime);
        constraints.put("cuisines", req.get("cuisines"));
        constraints.put("pattern", pattern);
        constraints.put("meals", meals);
        constraints.put("days", days);
        constraints.put("title", title);

        CookingMenu menu = CookingMenu.builder()
                .userId(userId)
                .menuWeek(startDate)
                .days(days)
                .title(title)
                .generatedBy("ai")
                .constraintsJson(toJson(constraints))
                .itemsJson(toJson(kept))
                .status("preview")
                .build();
        menuRepository.insert(menu);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("menuId", menu.getId());
        out.put("menuWeek", startDate.toString());
        out.put("startDate", startDate.toString());
        out.put("days", days);
        out.put("title", title);
        out.put("items", kept);
        out.put("excluded", excluded);
        out.put("missingDishes", missing);
        out.put("constraints", constraints);
        out.put("status", "preview");
        out.put("remainingToday", Math.max(0, dailyRateLimit - used));
        return out;
    }

    // ================= 确认 / 放弃 / 查询 =================

    public Map<String, Object> confirm(Long userId, Long menuId) {
        return confirm(userId, menuId, null);
    }

    /**
     * 确认菜单 → 写入 cooking_plans + items。
     * selectedRecipeIds 非空时仅写入勾选的条目（recipe_id 匹配），为空/null 写全部。
     */
    public Map<String, Object> confirm(Long userId, Long menuId, List<Long> selectedRecipeIds) {
        CookingMenu menu = requireOwnedPreview(userId, menuId);
        List<Map<String, Object>> items = readJsonList(menu.getItemsJson());
        if (items.isEmpty()) throw new IllegalArgumentException("菜单为空，无法写入计划");

        CookingPlan plan = CookingPlan.builder()
                .userId(userId)
                .title(menu.getTitle() != null && !menu.getTitle().isBlank() ? menu.getTitle() : "AI 周菜单")
                .planType("weekly")
                .startDate(menu.getMenuWeek())
                .menuId(menuId)
                .generatedBy("ai")
                .build();
        planRepository.insert(plan);

        for (Map<String, Object> it : items) {
            int day = it.get("day_offset") instanceof Number n ? n.intValue() : 0;
            String meal = it.get("meal_type") instanceof String s && !s.isBlank() ? s : "dinner";
            String name = it.get("dish_name") instanceof String s ? s : "家常菜";
            Long recipeId = it.get("recipe_id") instanceof Number n ? n.longValue() : null;
            // 勾选子集：仅写入选中条目
            if (selectedRecipeIds != null && !selectedRecipeIds.isEmpty()
                    && (recipeId == null || !selectedRecipeIds.contains(recipeId))) {
                continue;
            }
            RecipeMetadata r = recipeId != null ? recipeRepository.selectById(recipeId) : null;
            String dishType = it.get("dish_type") instanceof String s && !s.isBlank() ? s : null;
            itemRepository.insert(CookingPlanItem.builder()
                    .planId(plan.getId())
                    .dayOffset(day)
                    .videoId(r != null ? r.getVideoId() : null)
                    .recipeId(recipeId)
                    .recipeTitle(name.trim())
                    .mealType(meal)
                    .dishType(dishType)
                    .execMode("self")
                    .portionScale(new java.math.BigDecimal("1.00"))
                    .portionLocked(0)
                    .done(0)
                    .build());
        }

        // 全部被勾选掉则撤销计划（避免空计划）
        long written = itemRepository.findByPlanId(plan.getId()).size();
        if (written == 0) {
            planRepository.softDeleteById(plan.getId());
            menu.setStatus("discarded");
            menuRepository.updateById(menu);
            throw new IllegalArgumentException("未勾选任何菜品，菜单未写入");
        }

        menu.setPlanId(plan.getId());
        menu.setStatus("confirmed");
        menuRepository.updateById(menu);
        return planMap(plan);
    }

    public Map<String, Object> discard(Long userId, Long menuId) {
        CookingMenu menu = requireOwnedPreview(userId, menuId);
        menu.setStatus("discarded");
        menuRepository.updateById(menu);
        Map<String, Object> m = new HashMap<>();
        m.put("discarded", true);
        return m;
    }

    public List<Map<String, Object>> list(Long userId, String weekStr) {
        LocalDate start = parseStartDate(weekStr == null || weekStr.isBlank() ? null : weekStr);
        List<CookingMenu> menus = menuRepository.findByUserAndWeek(userId, start);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CookingMenu m : menus) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.getId());
            mm.put("menuWeek", m.getMenuWeek().toString());
            mm.put("startDate", m.getMenuWeek().toString());
            mm.put("days", m.getDays());
            mm.put("title", m.getTitle());
            mm.put("status", m.getStatus());
            mm.put("planId", m.getPlanId());
            mm.put("items", readJsonList(m.getItemsJson()));
            out.add(mm);
        }
        return out;
    }

    // ================= 内部：双轮编排 =================

    private List<Map<String, Object>> doRound1(String context) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", ROUND1_SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", context));
        String raw;
        try {
            raw = deepSeekClient.chat(messages, true);
        } catch (RuntimeException e) {
            log.error("[计划] Round1 调用失败: {}", e.getMessage());
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            List<Map<String, Object>> dishes = new ArrayList<>();
            JsonNode arr = node.path("dishes");
            if (arr.isArray()) {
                for (JsonNode d : arr) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.path("name").asText(""));
                    m.put("day_offset", d.path("day_offset").asInt(0));
                    m.put("meal_type", d.path("meal_type").asText("dinner"));
                    m.put("dish_type", d.path("dish_type").asText(""));
                    m.put("reason", d.path("reason").asText(""));
                    if (!String.valueOf(m.get("name")).isBlank()) dishes.add(m);
                }
            }
            return dishes;
        } catch (Exception e) {
            log.warn("[计划] Round1 解析失败 raw={}", raw, e);
            return List.of();
        }
    }

    private List<Map<String, Object>> doRound2(Long userId, String context,
                                               Map<String, List<RecipeMetadata>> candidatesByDish,
                                               String mode) {
        StringBuilder candSb = new StringBuilder();
        int idx = 0;
        for (Map.Entry<String, List<RecipeMetadata>> e : candidatesByDish.entrySet()) {
            for (RecipeMetadata r : e.getValue()) {
                candSb.append(idx++).append(". {id:").append(r.getId())
                        .append(", name:\"").append(r.getTitle()).append("\"")
                        .append(", calories:").append(r.getCalories() == null ? "null" : r.getCalories())
                        .append(", taste:\"").append(nullToEmpty(r.getTasteTags())).append("\"")
                        .append(", cuisine:\"").append(nullToEmpty(r.getCuisine())).append("\"")
                        .append(", main:\"").append(nullToEmpty(r.getMainIngredients())).append("\"}")
                        .append("\n");
            }
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", ROUND2_SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content",
                "用户约束：\n" + context + "\n\n候选菜谱列表：\n" + candSb));
        String raw;
        try {
            raw = deepSeekClient.chat(messages, true);
        } catch (RuntimeException e) {
            log.error("[计划] Round2 调用失败 userId={}: {}", userId, e.getMessage());
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            List<Map<String, Object>> items = new ArrayList<>();
            JsonNode arr = node.path("items");
            if (arr.isArray()) {
                for (JsonNode it : arr) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("day_offset", it.path("day_offset").asInt(0));
                    m.put("meal_type", it.path("meal_type").asText("dinner"));
                    m.put("dish_name", it.path("dish_name").asText(""));
                    m.put("recipe_id", it.path("recipe_id").asLong(0));
                    m.put("note", it.path("note").asText(""));
                    items.add(m);
                }
            }
            return items;
        } catch (Exception e) {
            log.warn("[计划] Round2 解析失败 raw={}", raw, e);
            return List.of();
        }
    }

    /** 降级：Round2 失败时每道菜取召回的最佳候选（favorites=加权分，random=随机） */
    private List<Map<String, Object>> fallbackPick(Long userId,
                                                   Map<String, List<RecipeMetadata>> candidatesByDish,
                                                   String mode) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, List<RecipeMetadata>> e : candidatesByDish.entrySet()) {
            List<RecipeMetadata> cands = e.getValue();
            if (cands.isEmpty()) continue;
            RecipeMetadata pick;
            if ("favorites".equals(mode)) {
                pick = cands.get(0); // 已在 recallCandidates 按加权排序
            } else {
                pick = cands.get(0);
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("day_offset", 0);
            it.put("meal_type", "dinner");
            it.put("dish_name", e.getKey());
            it.put("recipe_id", pick.getId());
            it.put("note", "自动挑选");
            items.add(it);
        }
        return items;
    }

    // ================= 候选召回 =================

    private List<RecipeMetadata> recallCandidates(Long userId, String dishName, String mode, int limit) {
        List<RecipeMetadata> all = recipeRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RecipeMetadata>()
                        .like(RecipeMetadata::getTitle, dishName));
        List<RecipeMetadata> visible = new ArrayList<>();
        for (RecipeMetadata r : all) {
            if (isCandidateVisible(r, userId)) visible.add(r);
        }
        // favorites 模式：收藏 +50，再叠加偏好匹配分排序
        Set<Long> favIds = new HashSet<>();
        if ("favorites".equals(mode)) {
            for (UserFavorite f : favoriteRepository.findByUserId(userId)) {
                if (f.getRecipeId() != null) favIds.add(f.getRecipeId());
            }
        }
        List<RecipeMetadata> ranked = new ArrayList<>(visible);
        ranked.sort((a, b) -> {
            int sa = score(a, favIds, userId);
            int sb = score(b, favIds, userId);
            return Integer.compare(sb, sa);
        });
        return ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked;
    }

    private int score(RecipeMetadata r, Set<Long> favIds, Long userId) {
        int s = 0;
        if (favIds.contains(r.getId())) s += 50;
        try {
            s += preferenceService.matchScore(userId, r);
        } catch (Exception ignored) {
        }
        return s;
    }

    private boolean isCandidateVisible(RecipeMetadata r, Long userId) {
        if (r == null) return false;
        if ("ai_creation".equals(r.getSourceType())) return true;
        if ("open_source".equals(r.getSourceType())) return true;
        if (RecipeMetadata.SOURCE_USER.equals(r.getSource())
                || RecipeMetadata.SOURCE_ARTICLE_LINK.equals(r.getSource())) {
            return userId != null && userId.equals(r.getUserId());
        }
        // VIDEO_LINK：假定候选可看（跟做/详情时再校验视频可见性兜底）
        return r.getVideoId() != null;
    }

    // ================= 用户上下文 =================

    private String buildUserContext(Long userId, String mode, boolean respectTaboos,
                                    boolean respectMeds, int maxCookTime,
                                    String pattern, List<String> meals,
                                    LocalDate startDate, int days) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> targets = healthProfileService.targets(userId);
        sb.append("【健康目标】每日热量约 ").append(targets.get("calories")).append(" kcal")
                .append("（蛋白 ").append(targets.get("protein")).append("g / 脂肪 ")
                .append(targets.get("fat")).append("g / 碳水 ").append(targets.get("carbs")).append("g）\n");
        String profile = xunweiMemoryService.profileToPrompt(userId);
        if (profile != null && !profile.isBlank()) sb.append("【用户画像】").append(profile).append("\n");
        sb.append("【生成模式】").append("favorites".equals(mode) ? "收藏/做过优先" : "随机生成").append("\n");
        sb.append("【遵循口忌】").append(respectTaboos ? "是（严格拦截忌口/过敏原）" : "否（仅标注）").append("\n");
        sb.append("【遵循用药】").append(respectMeds ? "是（拦截与用药冲突的菜）" : "否").append("\n");
        if (maxCookTime > 0) sb.append("【每道菜用时上限】").append(maxCookTime).append(" 分钟内\n");
        sb.append("【覆盖餐次】").append(mealLabels(meals)).append("\n");
        sb.append("【每餐荤素搭配】").append(patternLabel(pattern)).append("（每天每餐均按此出菜）\n");
        sb.append("\n请生成从 ").append(startDate).append(" 起未来 ").append(days)
                .append(" 天（day_offset: 0=").append(startDate)
                .append("，1=次日，依此类推）的做菜计划：每天").append(mealLabels(meals))
                .append("各").append(patternMeat(pattern) + patternVeg(pattern)).append(" 道，其中荤")
                .append(patternMeat(pattern)).append(" 道、素").append(patternVeg(pattern)).append(" 道。");
        return sb.toString();
    }

    // ================= 荤素搭配/餐次 工具 =================

    private List<String> parseMeals(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                String s = String.valueOf(o);
                if (!s.isBlank() && !out.contains(s)) out.add(s);
            }
        }
        if (out.isEmpty()) out.add("dinner");
        List<String> ordered = new ArrayList<>();
        for (String m : new String[]{"breakfast", "lunch", "dinner"}) {
            if (out.contains(m)) ordered.add(m);
        }
        return ordered.isEmpty() ? List.of("dinner") : ordered;
    }

    private String mealLabels(List<String> meals) {
        List<String> labels = new ArrayList<>();
        for (String m : meals) {
            labels.add(switch (m) {
                case "breakfast" -> "早餐";
                case "lunch" -> "午餐";
                case "dinner" -> "晚餐";
                case "snack" -> "加餐";
                default -> m;
            });
        }
        return String.join("+", labels);
    }

    private int patternMeat(String pattern) {
        return switch (normalizePattern(pattern)) {
            case "1_2" -> 1;
            case "2_2" -> 2;
            default -> 1;
        };
    }

    private int patternVeg(String pattern) {
        return switch (normalizePattern(pattern)) {
            case "2_2" -> 2;
            case "1_2" -> 2;
            default -> 1;
        };
    }

    private String patternLabel(String pattern) {
        return switch (normalizePattern(pattern)) {
            case "1_2" -> "一荤两素";
            case "2_2" -> "两荤两素";
            default -> "一荤一素";
        };
    }

    private String normalizePattern(String pattern) {
        if (pattern == null) return "1_1";
        String p = pattern.trim();
        if (p.matches("\\d+meat\\d+veg")) {
            return p.replace("meat", "_").replace("veg", "");
        }
        if (p.matches("\\d+_\\d+")) return p;
        return "1_1";
    }

    private Map<String, Object> pickFill(List<RecipeMetadata> pool, Set<Long> usedFill, Long userId,
                                         String needType, boolean respectTaboos, boolean respectMeds) {
        RecipeMetadata fallback = null;
        for (RecipeMetadata r : pool) {
            if (r == null || !needType.equals(classifyMeatVeg(r, userId))) continue;
            if (!passesSafety(r, userId, respectTaboos, respectMeds)) continue;
            if (fallback == null) fallback = r;
            if (!usedFill.contains(r.getId())) {
                usedFill.add(r.getId());
                return toFillItem(r, userId, needType);
            }
        }
        if (fallback != null) {
            usedFill.add(fallback.getId());
            return toFillItem(fallback, userId, needType);
        }
        return null;
    }

    private Map<String, Object> toFillItem(RecipeMetadata r, Long userId, String type) {
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("dish_name", r.getTitle());
        it.put("recipe_id", r.getId());
        it.put("note", "自动补全");
        it.put("nutrition", computeNutrition(r));
        it.put("taste_tags", r.getTasteTags());
        return it;
    }

    private boolean passesSafety(RecipeMetadata r, Long userId, boolean respectTaboos, boolean respectMeds) {
        if (respectTaboos) {
            Map<String, Object> eval = preferenceService.evaluateMerged(userId, r);
            if ("RED".equals(eval.get("level"))) return false;
        }
        if (respectMeds) {
            List<String> ings = extractIngredientNames(r);
            try {
                for (Map<String, Object> risk : safetyCheckService.checkForUser(userId, ings, null)) {
                    if ("MED".equals(risk.get("category")) && "RED".equals(risk.get("level"))) return false;
                }
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    /** 荤素判定：主料（recipe_ingredients isMain=1，兜底全部/主料字符串）命中 肉类/海鲜/蛋奶 → 荤；否则素 */
    private String classifyMeatVeg(RecipeMetadata r, Long userId) {
        if (r == null) return "veg";
        List<String> mains = new ArrayList<>();
        boolean anyMain = false;
        try {
            for (RecipeIngredient ig : ingredientRepository.findByRecipeId(r.getId())) {
                if (ig.getName() == null || ig.getName().isBlank()) continue;
                if (ig.getIsMain() != null && ig.getIsMain() == 1) {
                    mains.add(ig.getName());
                    anyMain = true;
                }
            }
        } catch (Exception ignored) {
        }
        if (!anyMain) {
            try {
                for (RecipeIngredient ig : ingredientRepository.findByRecipeId(r.getId())) {
                    if (ig.getName() != null && !ig.getName().isBlank()) mains.add(ig.getName());
                }
            } catch (Exception ignored) {
            }
        }
        if (mains.isEmpty() && r.getMainIngredients() != null) {
            for (String t : r.getMainIngredients().split("[,，、]")) {
                String s = t.trim();
                if (!s.isEmpty()) mains.add(s);
            }
        }
        for (String name : mains) {
            if (isMeatIngredient(name)) return "meat";
        }
        return "veg";
    }

    private boolean isMeatIngredient(String name) {
        if (name == null || name.isBlank()) return false;
        try {
            List<NutritionFood> foods = foodLibraryService.search(name, null, null);
            if (!foods.isEmpty() && foods.get(0).getCategory() != null) {
                String cat = foods.get(0).getCategory();
                if ("肉类".equals(cat) || "海鲜".equals(cat) || "蛋奶".equals(cat)) return true;
                if ("蔬菜".equals(cat) || "豆类".equals(cat) || "主食".equals(cat)
                        || "水果".equals(cat) || "坚果".equals(cat) || "饮品".equals(cat)) return false;
            }
        } catch (Exception ignored) {
        }
        for (String kw : MEAT_KEYWORDS) {
            if (name.contains(kw)) return true;
        }
        return false;
    }

    // ================= 工具 =================

    private Map<String, Object> computeNutrition(RecipeMetadata r) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            List<RecipeIngredient> ings = ingredientRepository.findByRecipeId(r.getId());
            NutritionCalculator.NutritionResult nut = nutritionCalculator.compute(ings, 1.0);
            if (nut.matchedCount == 0 && r.getCalories() != null) {
                m.put("calories", r.getCalories());
            } else {
                m.put("calories", (int) Math.round(nut.calories));
                m.put("protein", (int) Math.round(nut.protein));
            }
        } catch (Exception e) {
            m.put("calories", r.getCalories());
        }
        return m;
    }

    private List<String> extractIngredientNames(RecipeMetadata r) {
        List<String> out = new ArrayList<>();
        try {
            for (RecipeIngredient ig : ingredientRepository.findByRecipeId(r.getId())) {
                if (ig.getName() != null && !ig.getName().isBlank()) out.add(ig.getName());
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty() && r.getMainIngredients() != null) {
            for (String t : r.getMainIngredients().split("[,，、]")) {
                String trim = t.trim();
                if (!trim.isEmpty()) out.add(trim);
            }
        }
        return out;
    }

    private CookingMenu requireOwnedPreview(Long userId, Long menuId) {
        CookingMenu menu = menuRepository.selectById(menuId);
        if (menu == null || !menu.getUserId().equals(userId)) {
            throw new IllegalArgumentException("菜单不存在或无权访问");
        }
        if (!"preview".equals(menu.getStatus())) {
            throw new IllegalArgumentException("该菜单已处理（确认/放弃），请重新生成");
        }
        return menu;
    }

    private Map<String, Object> planMap(CookingPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("title", p.getTitle());
        m.put("planType", p.getPlanType());
        m.put("startDate", p.getStartDate().toString());
        m.put("menuId", p.getMenuId());
        m.put("generatedBy", p.getGeneratedBy());
        return m;
    }

    private Map<String, Object> safeItem(Map<String, Object> it, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day_offset", it.getOrDefault("day_offset", 0));
        m.put("meal_type", it.getOrDefault("meal_type", "dinner"));
        m.put("dish_name", it.getOrDefault("dish_name", ""));
        m.put("note", note != null ? note : it.getOrDefault("note", ""));
        if (it.get("recipe_id") instanceof Number n) m.put("recipe_id", n.longValue());
        return m;
    }

    private Map<String, Object> withReason(Map<String, Object> item, String reason) {
        Map<String, Object> m = new LinkedHashMap<>(item);
        m.put("excluded_reason", reason);
        return m;
    }

    private boolean boolOf(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s) || "1".equals(s);
        return def;
    }

    /** 起始日解析：默认今天；非法日期回退今天（v0.4 多天可配，不归一为周一） */
    private LocalDate parseStartDate(Object v) {
        LocalDate base = LocalDate.now();
        if (v instanceof String s && !s.isBlank()) {
            try {
                base = LocalDate.parse(s);
            } catch (DateTimeParseException e) {
                // 非法日期回退今天
            }
        }
        return base;
    }

    private String rateKey(Long userId) {
        return userId + ":" + LocalDate.now().toEpochDay();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (JsonNode n : node) {
                    out.add(objectMapper.convertValue(n, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    }));
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
