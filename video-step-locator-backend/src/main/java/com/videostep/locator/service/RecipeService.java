package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.RecipeDeviceParam;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.RecipeSource;
import com.videostep.locator.entity.User;
import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoStep;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.repository.RecipeDeviceParamRepository;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.RecipeSourceRepository;
import com.videostep.locator.repository.ShareLinkRepository;
import com.videostep.locator.repository.UserRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.VideoShareRepository;
import com.videostep.locator.repository.VideoStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeService {

    private final RecipeMetadataRepository recipeRepository;
    private final VideoRepository videoRepository;
    private final ShareLinkRepository shareLinkRepository;
    private final VideoShareRepository videoShareRepository;
    private final VideoStepRepository videoStepRepository;
    private final AiCreationRepository aiCreationRepository;
    private final PreferenceService preferenceService;
    private final RecipeIngredientRepository ingredientRepository;
    private final RecipeSourceRepository sourceRepository;
    private final RecipeDeviceParamRepository deviceParamRepository;
    private final UserRepository userRepository;
    private final NutritionCalculator nutritionCalculator;
    private final RecipeValidationService recipeValidationService;
    private final PrecisionScoreService precisionScoreService;
    private final RecipeProvenanceService provenanceService;
    private final com.videostep.locator.service.device.DeviceCommandService deviceCommandService;
    private final ObjectMapper objectMapper;

    /** 菜谱列表（全量平铺数组，兼容 APP/游客；无分页参数）。P3：登录用户按画像匹配度从高到低排序。 */
    public List<Map<String, Object>> list(String cuisine, String taste, String source, String type,
                                          BigDecimal minScore, Long viewerUserId) {
        return filteredRecipes(cuisine, taste, source, type, minScore, viewerUserId);
    }

    /** 菜谱列表（分页，Web 管理页用）：返回 {items,total,page,size,totalPages}，避免长列表垂直滚动。
     *  优化（2026-08-16）：两阶段——①轻量投影（不含大 JSON）+ 批量预取视频后过滤 + matchScore 排序，仅取当前页 id；
     *  ②批量加载当前页完整记录 + 批量预取视频，仅对当前页 toMap（消除全表大 JSON 传输与 N+1 视频查询）。
     */
    public Map<String, Object> listPaged(String cuisine, String taste, String source, String type,
                                         BigDecimal minScore, Long viewerUserId,
                                         int page, int size) {
        Map<String, Object> pg = pagedCandidates(cuisine, taste, source, type, minScore, viewerUserId, page, size);
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) pg.remove("ids");
        List<RecipeMetadata> full = ids.isEmpty() ? List.of() : recipeRepository.selectBatchIds(ids);
        Map<Long, RecipeMetadata> byId = full.stream()
                .collect(Collectors.toMap(RecipeMetadata::getId, r -> r));
        // 保持 matchScore 排序后的 id 顺序
        List<RecipeMetadata> ordered = ids.stream()
                .map(byId::get).filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, Video> videoMap = fetchVideos(ordered);
        List<Map<String, Object>> items = ordered.stream()
                .map(r -> toMap(r, viewerUserId, videoMap))
                .collect(Collectors.toList());
        pg.put("items", items);
        return pg;
    }

    /** 阶段1：轻量投影（不含 ingredients_json/steps_json 等大字段）+ 过滤 + 排序，返回当前页 id 与分页元信息 */
    private Map<String, Object> pagedCandidates(String cuisine, String taste, String source, String type,
                                                BigDecimal minScore, Long viewerUserId, int page, int size) {
        LambdaQueryWrapper<RecipeMetadata> qw = new LambdaQueryWrapper<RecipeMetadata>()
                .select(RecipeMetadata::getId, RecipeMetadata::getVideoId, RecipeMetadata::getUserId,
                        RecipeMetadata::getTitle, RecipeMetadata::getMainIngredients,
                        RecipeMetadata::getAuxiliaryIngredients, RecipeMetadata::getTasteTags,
                        RecipeMetadata::getAllergens, RecipeMetadata::getSpiceLevel, RecipeMetadata::getCuisine,
                        RecipeMetadata::getEstimatedTime, RecipeMetadata::getCalories,
                        RecipeMetadata::getSourceType, RecipeMetadata::getSource, RecipeMetadata::getRecipeType,
                        RecipeMetadata::getPrecisionScore)
                .eq(type != null && !type.isBlank(), RecipeMetadata::getRecipeType, type)
                .eq(source != null && !source.isBlank(), RecipeMetadata::getSource, source)
                .eq(cuisine != null && !cuisine.isBlank(), RecipeMetadata::getCuisine, cuisine)
                .ge(minScore != null, RecipeMetadata::getPrecisionScore, minScore)
                .like(taste != null && !taste.isBlank(), RecipeMetadata::getTasteTags, taste);
        List<RecipeMetadata> all = recipeRepository.selectList(qw);
        // 批量预取候选菜谱的视频（避免 isVisibleRecipe 逐条 selectById 的 N+1）
        Map<Long, Video> videoMap = fetchVideos(all);
        List<RecipeMetadata> candidates = all.stream()
                .filter(r -> isVisibleRecipe(r, viewerUserId, videoMap))
                .collect(Collectors.toList());
        // P3 个性化排序：贴合用户偏好的菜谱排前面（登录时；轻量规则，候选集计算可接受）
        if (viewerUserId != null && !candidates.isEmpty()) {
            Map<Long, Integer> scoreCache = new HashMap<>();
            for (RecipeMetadata r : candidates) scoreCache.put(r.getId(), preferenceService.matchScore(viewerUserId, r));
            candidates.sort((a, b) -> Integer.compare(scoreCache.getOrDefault(b.getId(), 0),
                                                      scoreCache.getOrDefault(a.getId(), 0)));
        }
        int total = candidates.size();
        int safeSize = Math.max(1, size);
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
        int p = Math.max(0, Math.min(page, totalPages - 1));
        int from = p * safeSize;
        int to = Math.min(total, from + safeSize);
        List<Long> ids = candidates.subList(from, to).stream()
                .map(RecipeMetadata::getId).collect(Collectors.toList());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ids", ids);
        res.put("total", total);
        res.put("page", p);
        res.put("size", safeSize);
        res.put("totalPages", totalPages);
        return res;
    }

    /** 批量预取视频（避免逐条 selectById 的 N+1）；返回 HashMap（保证 get(null) 安全） */
    private Map<Long, Video> fetchVideos(List<RecipeMetadata> list) {
        java.util.Set<Long> ids = list.stream()
                .map(RecipeMetadata::getVideoId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return new HashMap<>();
        return videoRepository.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Video::getId, v -> v));
    }

    /** 过滤 + 画像匹配度排序的菜谱列表（list / listPaged 共用） */
    private List<Map<String, Object>> filteredRecipes(String cuisine, String taste, String source, String type,
                                                      BigDecimal minScore, Long viewerUserId) {
        List<Map<String, Object>> result = recipeRepository.selectList(null).stream()
                .filter(r -> isVisibleRecipe(r, viewerUserId))
                .filter(r -> cuisine == null || cuisine.isBlank() || cuisine.equals(r.getCuisine()))
                .filter(r -> taste == null || taste.isBlank()
                        || (r.getTasteTags() != null && r.getTasteTags().contains(taste)))
                .filter(r -> source == null || source.isBlank() || source.equalsIgnoreCase(r.getSource()))
                .filter(r -> type == null || type.isBlank()
                        || type.equalsIgnoreCase(r.getRecipeType() != null ? r.getRecipeType() : RecipeMetadata.TYPE_HOME))
                .filter(r -> minScore == null || (r.getPrecisionScore() != null
                        && r.getPrecisionScore().compareTo(minScore) >= 0))
                .map(r -> {
                    Map<String, Object> m = toMap(r, viewerUserId);
                    if (viewerUserId != null) {
                        m.put("matchScore", preferenceService.matchScore(viewerUserId, r));
                    }
                    return m;
                })
                .collect(Collectors.toList());
        // P3 个性化排序：贴合用户偏好（显式设置 + 寻味学习画像）的菜谱排前面
        if (viewerUserId != null) {
            result.sort((a, b) -> Integer.compare(
                    ((Number) b.getOrDefault("matchScore", 0)).intValue(),
                    ((Number) a.getOrDefault("matchScore", 0)).intValue()));
        }
        return result;
    }

    public Map<String, Object> detail(Long recipeId, Long viewerUserId) {
        RecipeMetadata r = recipeRepository.selectById(recipeId);
        if (r == null) throw new IllegalArgumentException("菜谱不存在");
        if (!isVisibleRecipe(r, viewerUserId)) throw new SecurityException("无权访问");
        Map<String, Object> m = toMap(r, viewerUserId);
        // 统一模型 v0.2：食材明细（权威 recipe_ingredients）+ 基准份量营养估算
        List<RecipeIngredient> ingredients = ingredientRepository.findByRecipeId(recipeId);
        m.put("ingredients", ingredients.stream().map(this::ingredientToMap).collect(Collectors.toList()));
        m.put("nutrition", nutritionCalculator.compute(ingredients, 1.0).toMap());
        m.put("baseServings", r.getServings() != null && r.getServings() > 0 ? r.getServings() : 1);
        // 操作步骤：优先 steps_json；缺失时按来源回退（视频→video_steps，AI 镜像→ai_creations.steps），保证各来源展示一致
        List<Map<String, Object>> steps = readJsonList(r.getStepsJson());
        if (steps.isEmpty() && r.getVideoId() != null) {
            steps = videoStepRepository.findByVideoIdOrderByStepIndexAsc(r.getVideoId()).stream()
                    .map(vs -> {
                        Map<String, Object> sm = new LinkedHashMap<>();
                        sm.put("summary", vs.getSummary());
                        sm.put("detail", vs.getOriginalText());
                        return sm;
                    })
                    .collect(Collectors.toList());
        }
        if (steps.isEmpty() && r.getCreationId() != null) {
            AiCreation c = aiCreationRepository.selectById(r.getCreationId());
            if (c != null) steps = readJsonList(c.getSteps());
        }
        m.put("steps", steps);
        // 厨电参数（流水线 device_params → recipe_device_params，工业型下发数据源）
        List<RecipeDeviceParam> deviceParams = deviceCommandService.getRecipeDeviceParams(recipeId);
        // 补齐：非视频菜谱（图文/手动/AI）步骤含 温度/模式 但缺设备参数 → 按步骤派生（幂等懒回填）
        if (r.getVideoId() == null && deviceParams.isEmpty()) {
            boolean hasCookware = steps.stream().anyMatch(s ->
                    s.get("temperature") != null
                            || (s.get("mode") != null && !String.valueOf(s.get("mode")).isBlank()));
            if (hasCookware) {
                deviceCommandService.deriveDeviceParamsFromSteps(recipeId, steps);
                deviceParams = deviceCommandService.getRecipeDeviceParams(recipeId);
            }
        }
        m.put("deviceParams", deviceParams);
        return m;
    }

    /** 精确化评分分布（供阈值校准，设计文档 §7.5/§11） */
    public Map<String, Object> precisionStats() {
        List<RecipeMetadata> all = recipeRepository.selectList(null);
        int industrial = 0, home = 0;
        int[] bands = new int[5];
        for (RecipeMetadata r : all) {
            double v = r.getPrecisionScore() == null ? 0 : r.getPrecisionScore().doubleValue();
            if (v >= precisionScoreService.threshold()) industrial++;
            else home++;
            if (v < 0.3) bands[0]++;
            else if (v < 0.5) bands[1]++;
            else if (v < 0.7) bands[2]++;
            else if (v < 0.85) bands[3]++;
            else bands[4]++;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("threshold", precisionScoreService.threshold());
        res.put("total", all.size());
        res.put("industrialCount", industrial);
        res.put("homeCount", home);
        List<Map<String, Object>> bandList = new ArrayList<>();
        bandList.add(Map.of("label", "<0.3", "min", 0.0, "max", 0.3, "count", bands[0]));
        bandList.add(Map.of("label", "0.3~0.5", "min", 0.3, "max", 0.5, "count", bands[1]));
        bandList.add(Map.of("label", "0.5~0.7", "min", 0.5, "max", 0.7, "count", bands[2]));
        bandList.add(Map.of("label", "0.7~0.85", "min", 0.7, "max", 0.85, "count", bands[3]));
        bandList.add(Map.of("label", ">=0.85", "min", 0.85, "max", 1.0, "count", bands[4]));
        res.put("bands", bandList);
        return res;
    }

    /** 可见性校验并返回实体（供 scale/upgrade 等复用） */
    public RecipeMetadata requireVisible(Long recipeId, Long viewerUserId) {
        RecipeMetadata r = recipeRepository.selectById(recipeId);
        if (r == null) throw new IllegalArgumentException("菜谱不存在");
        if (!isVisibleRecipe(r, viewerUserId)) throw new SecurityException("无权访问");
        return r;
    }

    /** B 端：企业菜谱库（按 org_id 隔离），供企业管理页展示 */
    public List<Map<String, Object>> listForOrg(Long orgId, String cuisine, String taste) {
        return recipeRepository.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RecipeMetadata>()
                                .eq(RecipeMetadata::getOrgId, orgId))
                .stream()
                .filter(r -> cuisine == null || cuisine.isBlank() || cuisine.equals(r.getCuisine()))
                .filter(r -> taste == null || taste.isBlank()
                        || (r.getTasteTags() != null && r.getTasteTags().contains(taste)))
                .map(r -> toMap(r, null))
                .collect(Collectors.toList());
    }

    public Map<String, Object> compatibility(Long recipeId, Long userId) {
        RecipeMetadata r = recipeRepository.selectById(recipeId);
        if (r == null) throw new IllegalArgumentException("菜谱不存在");
        Map<String, Object> result = new HashMap<>();
        result.put("videoId", r.getVideoId());
        result.put("compatibility", preferenceService.checkCompatibility(userId, r));
        return result;
    }

    private boolean isVisible(Long videoId, Long viewerUserId) {
        return isVisible(videoId, viewerUserId, null);
    }

    private boolean isVisible(Long videoId, Long viewerUserId, Map<Long, Video> videoMap) {
        Video v = videoMap != null ? videoMap.get(videoId) : videoRepository.selectById(videoId);
        if (v == null) return false;
        if ("public".equals(v.getVisibility())) return true;
        return viewerUserId != null && v.getUserId().equals(viewerUserId);
    }

    /** AI 创作镜像（sourceType='ai_creation'）/ 开源内容库（sourceType='open_source'）公开可见；USER/ARTICLE_LINK 菜谱仅创建者可见；其余按视频可见性 */
    private boolean isVisibleRecipe(RecipeMetadata r, Long viewerUserId) {
        return isVisibleRecipe(r, viewerUserId, null);
    }

    private boolean isVisibleRecipe(RecipeMetadata r, Long viewerUserId, Map<Long, Video> videoMap) {
        if ("ai_creation".equals(r.getSourceType())) return true;
        // open_source：HowToCook 等开源内容源导入的公开菜谱库（content-sync，P1）
        if ("open_source".equals(r.getSourceType())) return true;
        if (RecipeMetadata.SOURCE_USER.equals(r.getSource())
                || RecipeMetadata.SOURCE_ARTICLE_LINK.equals(r.getSource())) {
            return viewerUserId != null && viewerUserId.equals(r.getUserId());
        }
        return isVisible(r.getVideoId(), viewerUserId, videoMap);
    }

    private Map<String, Object> toMap(RecipeMetadata r, Long viewerUserId) {
        return toMap(r, viewerUserId, null);
    }

    /** toMap（支持批量预取视频，避免逐条 selectById 的 N+1） */
    private Map<String, Object> toMap(RecipeMetadata r, Long viewerUserId, Map<Long, Video> videoMap) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("videoId", r.getVideoId());
        m.put("title", r.getTitle() != null ? r.getTitle() : "");
        m.put("summary", r.getSummary());
        m.put("mainIngredients", r.getMainIngredients());
        m.put("auxiliaryIngredients", r.getAuxiliaryIngredients());
        m.put("tasteTags", r.getTasteTags());
        m.put("allergens", r.getAllergens());
        m.put("spiceLevel", r.getSpiceLevel());
        m.put("cuisine", r.getCuisine());
        m.put("estimatedTime", r.getEstimatedTime());
        m.put("calories", r.getCalories());
        m.put("sourceType", r.getSourceType()); // AI 创作镜像='ai_creation'（前端显示「AI 创作」角标）
        // 统一模型 v0.2：来源/派生类型/精确化评分/溯源
        m.put("source", r.getSource() != null ? r.getSource() : RecipeMetadata.SOURCE_VIDEO_LINK);
        m.put("recipeType", r.getRecipeType() != null ? r.getRecipeType() : RecipeMetadata.TYPE_HOME);
        m.put("precisionScore", r.getPrecisionScore());
        m.put("sourceUrl", r.getSourceUrl());
        m.put("refId", r.getRefId());
        m.put("extractorVersion", r.getExtractorVersion());
        m.put("extractedAt", r.getExtractedAt());
        m.put("confidence", r.getConfidence());
        m.put("servings", r.getServings());
        m.put("precisionNote", r.getPrecisionNote());
        m.put("userId", r.getUserId()); // 创建者（APP 我的菜谱管理用）
        // 视频信息：videoMap 非空时避免 get(null)（Java 不可变 Map 对 null key 抛 NPE）
        Video v = null;
        if (videoMap != null) {
            v = r.getVideoId() == null ? null : videoMap.get(r.getVideoId());
        } else if (r.getVideoId() != null) {
            v = videoRepository.selectById(r.getVideoId());
        }
        if (v != null) {
            m.put("videoTitle", v.getTitle());
            m.put("videoStatus", v.getStatus());
            m.put("visibility", v.getVisibility());
        }
        // 注册用户附加兼容性
        if (viewerUserId != null) {
            m.put("compatibility", preferenceService.checkCompatibility(viewerUserId, r));
        }
        return m;
    }

    /** USER 来源：新增菜谱（需登录）；工业型走 RecipeValidationService 强校验 */
    public Map<String, Object> createUserRecipe(Long userId, Map<String, Object> body) {
        RecipeMetadata rm = new RecipeMetadata();
        rm.setUserId(userId);
        // 支持 ARTICLE_LINK 来源（图文解析草稿保存）；默认 USER
        boolean isArticle = "ARTICLE_LINK".equalsIgnoreCase(str(body.get("source")));
        rm.setSource(isArticle ? RecipeMetadata.SOURCE_ARTICLE_LINK : RecipeMetadata.SOURCE_USER);
        rm.setExtractedAt(LocalDateTime.now());
        applyUserFields(rm, body, isArticle);
        recipeRepository.insert(rm);
        saveUserChildren(rm.getId(), body);
        provenanceService.record(rm);
        log.info("用户 {} 录入菜谱 id={} title={} type={} score={}", userId, rm.getId(),
                rm.getTitle(), rm.getRecipeType(), rm.getPrecisionScore());
        return detail(rm.getId(), userId);
    }

    /** 修改菜谱（创建者或管理员）；保留 来源/溯源/userId，重算评分/类型并重存 食材/步骤/设备参数 */
    public Map<String, Object> updateRecipe(Long operatorId, Long recipeId, Map<String, Object> body) {
        RecipeMetadata rm = recipeRepository.selectById(recipeId);
        if (rm == null) throw new IllegalArgumentException("菜谱不存在");
        boolean owner = rm.getUserId() != null && rm.getUserId().equals(operatorId);
        if (!owner && !isAdmin(operatorId)) throw new SecurityException("仅菜谱创建者或管理员可修改");
        // 关联视频已发布/已共享 → 菜谱锁定（管理员豁免）；修改后关联视频审核失效
        if (!isAdmin(operatorId) && rm.getVideoId() != null && isVideoLocked(rm.getVideoId())) {
            throw new IllegalArgumentException("关联视频已发布或共享，菜谱已锁定；请先取消发布/共享后再修改");
        }
        boolean isArticle = RecipeMetadata.SOURCE_ARTICLE_LINK.equals(rm.getSource());
        applyUserFields(rm, body, isArticle);
        recipeRepository.updateById(rm);
        saveUserChildren(recipeId, body);
        resetLinkedVideoReview(rm.getVideoId());
        log.info("用户 {} 修改菜谱 id={} title={} type={} score={}", operatorId, recipeId,
                rm.getTitle(), rm.getRecipeType(), rm.getPrecisionScore());
        return detail(recipeId, operatorId);
    }

    /** 关联视频是否已发布/已共享（锁定） */
    private boolean isVideoLocked(Long videoId) {
        Video v = videoRepository.selectById(videoId);
        if (v == null) return false;
        if (Video.VISIBILITY_PUBLIC.equals(v.getVisibility())
                || Video.VISIBILITY_SHARED.equals(v.getVisibility())) return true;
        return shareLinkRepository.countActiveByVideoId(videoId) > 0
                || videoShareRepository.countActiveByVideoId(videoId) > 0;
    }

    /** 菜谱修改后，关联视频审核回待审核 */
    private void resetLinkedVideoReview(Long videoId) {
        if (videoId == null) return;
        Video v = videoRepository.selectById(videoId);
        if (v == null) return;
        v.setReviewStatus(Video.REVIEW_PENDING);
        videoRepository.updateById(v);
    }

    /** 通用：应用录入/修改表单字段（校验 + 打分 + 派生类型），供 create/update 复用 */
    private void applyUserFields(RecipeMetadata rm, Map<String, Object> body, boolean isArticle) {
        String title = str(body.get("title"));
        if (title == null || title.isBlank()) throw new IllegalArgumentException("菜名不能为空");
        List<Map<String, Object>> ingredients = listOfMaps(body.get("ingredients"));
        List<Map<String, Object>> steps = listOfMaps(body.get("steps"));
        boolean requireIndustrial = Boolean.TRUE.equals(body.get("industrial"));
        recipeValidationService.validateUserRecipe(ingredients, steps, requireIndustrial);
        rm.setTitle(title.trim());
        rm.setSummary(str(body.get("summary")));
        rm.setServings(intOf(body.get("servings")));
        rm.setTasteTags(joinTags(body.get("tasteTags")));
        rm.setAllergens(joinTags(body.get("allergens")));
        rm.setSpiceLevel(intOf(body.get("spiceLevel")));
        rm.setCuisine(str(body.get("cuisine")));
        rm.setEstimatedTime(intOf(body.get("estimatedTime")));
        rm.setCalories(intOf(body.get("calories")));
        if (isArticle) {
            rm.setExtractorVersion("article-extract-v1");
            rm.setExtractorEngine("article+deepseek");
            if (str(body.get("sourceUrl")) != null) rm.setSourceUrl(str(body.get("sourceUrl")));
        } else {
            rm.setExtractorVersion("user-form-v1");
            rm.setExtractorEngine("user");
            rm.setSourceUrl(null);
        }
        BigDecimal score = precisionScoreService.compute(ingredients, steps, rm.getCalories() != null, true);
        rm.setPrecisionScore(score);
        rm.setRecipeType(precisionScoreService.deriveType(score));
        rm.setIngredientsJson(writeJson(ingredients));
        rm.setStepsJson(writeJson(steps));
    }

    /** 通用：重存 食材(recipe_ingredients) + 派生 厨电参数(recipe_device_params) */
    private void saveUserChildren(Long recipeId, Map<String, Object> body) {
        List<Map<String, Object>> ingredients = listOfMaps(body.get("ingredients"));
        List<Map<String, Object>> steps = listOfMaps(body.get("steps"));
        saveIngredients(recipeId, ingredients);
        // 厨电参数：非视频来源（图文/手动）步骤含 温度/模式 → 按步骤派生（与流水线一致，供厨电联动）
        deviceCommandService.deriveDeviceParamsFromSteps(recipeId, steps);
    }

    private void saveIngredients(Long recipeId, List<Map<String, Object>> ingredients) {
        ingredientRepository.deleteByRecipeId(recipeId);
        int seq = 1;
        for (Map<String, Object> ig : ingredients) {
            RecipeIngredient row = RecipeIngredient.builder()
                    .recipeId(recipeId)
                    .seq(seq++)
                    .name(str(ig.get("name")))
                    .amount(numOf(ig.get("amount")))
                    .unit(str(ig.get("unit")))
                    .tolerance(numOf(ig.get("tolerance")))
                    .isMain(isMainFlag(ig))
                    .note(str(ig.get("note")))
                    .build();
            ingredientRepository.insert(row);
        }
    }

    private Map<String, Object> ingredientToMap(RecipeIngredient ig) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ig.getId());
        m.put("seq", ig.getSeq());
        m.put("name", ig.getName());
        m.put("amount", ig.getAmount());
        m.put("unit", ig.getUnit());
        m.put("tolerance", ig.getTolerance());
        m.put("isMain", ig.getIsMain());
        m.put("note", ig.getNote());
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (x instanceof Map<?, ?> m) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        return out;
    }

    private String joinTags(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private BigDecimal numOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = String.valueOf(o).trim();
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    private Integer isMainFlag(Map<String, Object> ig) {
        Object v = ig.get("isMain") != null ? ig.get("isMain") : ig.get("is_main");
        if (v == null) return 1;
        String s = String.valueOf(v).trim();
        return ("1".equals(s) || "true".equalsIgnoreCase(s)) ? 1 : 0;
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Integer intOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    private String writeJson(Object o) {
        if (o == null) return null;
        try {
            String s = objectMapper.writeValueAsString(o);
            return "null".equals(s) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除菜谱（创建者或管理员）：先删子表（食材/溯源/厨电参数）再删主表 */
    public boolean deleteRecipe(Long recipeId, Long operatorId) {
        RecipeMetadata r = recipeRepository.selectById(recipeId);
        if (r == null) throw new IllegalArgumentException("菜谱不存在");
        if (operatorId == null) throw new SecurityException("请登录后删除菜谱");
        boolean owner = r.getUserId() != null && r.getUserId().equals(operatorId);
        if (!owner && !isAdmin(operatorId)) throw new SecurityException("仅菜谱创建者或管理员可删除");
        ingredientRepository.deleteByRecipeId(recipeId);
        sourceRepository.delete(new LambdaQueryWrapper<RecipeSource>().eq(RecipeSource::getRecipeId, recipeId));
        deviceParamRepository.delete(new LambdaQueryWrapper<RecipeDeviceParam>().eq(RecipeDeviceParam::getRecipeId, recipeId));
        recipeRepository.deleteById(recipeId);
        log.info("用户 {} 删除菜谱 id={} title={}", operatorId, recipeId, r.getTitle());
        return true;
    }

    private boolean isAdmin(Long userId) {
        if (userId == null) return false;
        User u = userRepository.selectById(userId);
        return u != null && "ADMIN".equals(u.getRole());
    }

    /** 解析 recipes.steps_json → List<Map>（detail 响应返回 steps） */
    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("stepsJson 解析失败: {}", e.getMessage());
            return List.of();
        }
    }
}
