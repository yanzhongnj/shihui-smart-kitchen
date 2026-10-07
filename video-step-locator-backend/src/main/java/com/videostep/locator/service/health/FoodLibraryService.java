package com.videostep.locator.service.health;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.NutritionFood;
import com.videostep.locator.repository.NutritionFoodRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 食物营养库（nutrition_food_db）：启动幂等初始化种子 + 内存缓存 + 搜索/分类/用户自定义。
 * 缓存为营养计算的唯一查询源（DB 为权威），用户新增/回填后 reloadCache 保证一致。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodLibraryService {

    private final NutritionFoodRepository repository;

    /** 是否启动初始化核心食物（默认 true） */
    @Value("${health.nutrition.food-db-init:true}")
    private boolean foodDbInit;

    /** 内置 10 大类顺序 */
    private static final List<String> CATEGORY_ORDER = List.of(
            "主食", "蔬菜", "水果", "肉类", "海鲜", "蛋奶", "豆类", "坚果", "饮品", "其他");

    /** 名称 → 营养数据（缓存；DB 为权威，启动/新增后刷新） */
    private volatile Map<String, NutritionFood> cache = Collections.emptyMap();

    @PostConstruct
    public void init() {
        if (foodDbInit) {
            seedIfEmpty();
        }
        reloadCache();
        log.info("FoodLibraryService ready, foods={}, categories={}", cache.size(), categories().size());
    }

    /** 幂等初始化：表为空才插入种子（含核心 83 + 扩充），返回本次插入数 */
    public int seedIfEmpty() {
        long count = repository.selectCount(null);
        if (count > 0) {
            return 0;
        }
        int inserted = 0;
        for (FoodSeedData.Seed s : FoodSeedData.all()) {
            try {
                NutritionFood f = NutritionFood.builder()
                        .name(s.name())
                        .category(s.category())
                        .calories(BigDecimal.valueOf(s.cal()))
                        .protein(BigDecimal.valueOf(s.protein()))
                        .fat(BigDecimal.valueOf(s.fat()))
                        .carbs(BigDecimal.valueOf(s.carbs()))
                        .fiber(BigDecimal.valueOf(s.fiber()))
                        .sodium(BigDecimal.valueOf(s.sodium()))
                        .isCore(s.core() ? 1 : 0)
                        .source("seed")
                        .build();
                repository.insert(f);
                inserted++;
            } catch (Exception e) {
                // 唯一键冲突/已存在 → 跳过
                log.debug("seed skip {}: {}", s.name(), e.getMessage());
            }
        }
        log.info("FoodLibraryService seeded {} foods", inserted);
        return inserted;
    }

    /** 重建内存缓存 */
    public void reloadCache() {
        List<NutritionFood> all = repository.selectList(null);
        Map<String, NutritionFood> m = new HashMap<>();
        for (NutritionFood f : all) {
            m.put(f.getName(), f);
        }
        this.cache = m;
    }

    /** 精确名称查询（营养计算用） */
    public NutritionFood findByExact(String name) {
        if (name == null) return null;
        return cache.get(name.trim());
    }

    /** 模糊搜索 + 分类/来源过滤；keyword 为空仅按分类；最多返回 100 条 */
    public List<NutritionFood> search(String keyword, String category, String source) {
        LambdaQueryWrapper<NutritionFood> w = baseWrapper(keyword, category, source);
        w.orderByDesc(NutritionFood::getIsCore).orderByAsc(NutritionFood::getName);
        List<NutritionFood> list = repository.selectList(w);
        if (list.size() > 100) list = list.subList(0, 100);
        return list;
    }

    /** 分页搜索（管理端）：返回当页 items（isCore 优先，名称升序） */
    public List<NutritionFood> searchPage(String keyword, String category, String source, int page, int size) {
        int s = Math.max(1, size);
        LambdaQueryWrapper<NutritionFood> w = baseWrapper(keyword, category, source);
        w.orderByDesc(NutritionFood::getIsCore).orderByAsc(NutritionFood::getName);
        w.last("LIMIT " + s + " OFFSET " + (Math.max(0, page) * s));
        return repository.selectList(w);
    }

    /** 分页总数 */
    public long count(String keyword, String category, String source) {
        return repository.selectCount(baseWrapper(keyword, category, source));
    }

    private LambdaQueryWrapper<NutritionFood> baseWrapper(String keyword, String category, String source) {
        LambdaQueryWrapper<NutritionFood> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.like(NutritionFood::getName, keyword.trim());
        }
        if (category != null && !category.isBlank()) {
            w.eq(NutritionFood::getCategory, category.trim());
        }
        if (source != null && !source.isBlank()) {
            w.eq(NutritionFood::getSource, source.trim());
        }
        return w;
    }

    /** 分类列表（固定顺序，仅含已有数据的分类） */
    public List<Map<String, Object>> categories() {
        Set<String> existing = repository.selectList(null).stream()
                .map(NutritionFood::getCategory)
                .collect(Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : CATEGORY_ORDER) {
            if (existing.contains(c)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c);
                m.put("code", c);
                out.add(m);
            }
        }
        return out;
    }

    /** 用户自定义食材入库（source=user, 非核心）；重名报错 */
    public NutritionFood createUserFood(Long userId, String name, String category,
                                        BigDecimal calories, BigDecimal protein,
                                        BigDecimal fat, BigDecimal carbs,
                                        BigDecimal fiber, BigDecimal sodium) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("食材名称不能为空");
        String trimmed = name.trim();
        if (findByExact(trimmed) != null) throw new IllegalArgumentException("食材「" + trimmed + "」已存在");
        if (category == null || category.isBlank()) category = "其他";
        NutritionFood f = NutritionFood.builder()
                .name(trimmed)
                .category(category)
                .calories(calories == null ? BigDecimal.ZERO : calories)
                .protein(protein == null ? BigDecimal.ZERO : protein)
                .fat(fat == null ? BigDecimal.ZERO : fat)
                .carbs(carbs == null ? BigDecimal.ZERO : carbs)
                .fiber(fiber == null ? BigDecimal.ZERO : fiber)
                .sodium(sodium == null ? BigDecimal.ZERO : sodium)
                .isCore(0)
                .source("user")
                .build();
        repository.insert(f);
        reloadCache();
        return f;
    }

    /** AI 回填批量入库（source=ai, 非核心）；已存在则跳过（幂等），返回本次插入数 */
    public int createAiFoods(List<Map<String, Object>> foods) {
        if (foods == null || foods.isEmpty()) return 0;
        int inserted = 0;
        for (Map<String, Object> f : foods) {
            String name = f.get("name") instanceof String s ? s.trim() : null;
            if (name == null || name.isBlank()) continue;
            if (findByExact(name) != null) continue;
            String category = f.get("category") instanceof String s ? s : "其他";
            if (category == null || category.isBlank()) category = "其他";
            NutritionFood row = NutritionFood.builder()
                    .name(name)
                    .category(category)
                    .calories(dec(f.get("calories")))
                    .protein(dec(f.get("protein")))
                    .fat(dec(f.get("fat")))
                    .carbs(dec(f.get("carbs")))
                    .fiber(BigDecimal.ZERO)
                    .sodium(BigDecimal.ZERO)
                    .isCore(0)
                    .source("ai")
                    .build();
            try {
                repository.insert(row);
                inserted++;
            } catch (Exception e) {
                log.debug("ai food skip {}: {}", name, e.getMessage());
            }
        }
        if (inserted > 0) reloadCache();
        return inserted;
    }

    /** 管理端：更新食物营养（source 保持不变）；不存在报错 */
    public NutritionFood updateFood(Long id, String category, BigDecimal calories, BigDecimal protein,
                                    BigDecimal fat, BigDecimal carbs, BigDecimal fiber, BigDecimal sodium) {
        NutritionFood f = id == null ? null : repository.selectById(id);
        if (f == null) throw new IllegalArgumentException("食材不存在");
        if (category != null && !category.isBlank()) f.setCategory(category.trim());
        if (calories != null) f.setCalories(calories);
        if (protein != null) f.setProtein(protein);
        if (fat != null) f.setFat(fat);
        if (carbs != null) f.setCarbs(carbs);
        if (fiber != null) f.setFiber(fiber);
        if (sodium != null) f.setSodium(sodium);
        repository.updateById(f);
        reloadCache();
        return f;
    }

    /** 管理端：删除食物（核心/用户/AI 均可）；不存在报错 */
    public void deleteFood(Long id) {
        NutritionFood f = id == null ? null : repository.selectById(id);
        if (f == null) throw new IllegalArgumentException("食材不存在");
        repository.deleteById(id);
        reloadCache();
    }

    private BigDecimal dec(Object o) {
        return o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }
}
