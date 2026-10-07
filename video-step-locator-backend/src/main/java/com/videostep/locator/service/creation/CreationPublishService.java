package com.videostep.locator.service.creation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.PrecisionScoreService;
import com.videostep.locator.service.RecipeProvenanceService;
import com.videostep.locator.service.device.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酿味升 B（v0.4 + v0.2）：把 AI 创作镜像到统一菜谱库 recipes（source=AI_CREATE，source_url=null，ref_id+raw_digest 溯源）。
 * 入库后 AI 菜可被菜谱列表/推荐消费；搜索降权 + 「AI 创作」角标、B 端授权复用（content_licenses）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationPublishService {

    private final CreationChatService chatService;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final PrecisionScoreService precisionScoreService;
    private final RecipeProvenanceService provenanceService;
    private final DeviceCommandService deviceCommandService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> syncToLibrary(Long userId, Long creationId) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        RecipeMetadata meta = recipeMetadataRepository.findByCreationId(creationId);
        boolean isNew = meta == null;
        if (isNew) {
            meta = new RecipeMetadata();
            meta.setCreationId(creationId);
            meta.setSourceType(AiCreation.SOURCE_TYPE_AI_CREATION);
        }
        meta.setTitle(c.getTitle());
        meta.setSummary(c.getSummary());
        meta.setMainIngredients(join(c.getMainIngredients()));
        meta.setAuxiliaryIngredients(join(c.getAuxiliaryIngredients()));
        meta.setTasteTags(join(c.getTasteTags()));
        meta.setAllergens(join(c.getAllergens()));
        meta.setSpiceLevel(c.getSpiceLevel());
        meta.setEstimatedTime(c.getEstimatedTime());
        meta.setCalories(c.getCalories());
        // ---- 统一模型 v0.2：来源 / 溯源（AI 创作无外部 URL，source_url=null；ref_id+raw_digest 溯源）----
        meta.setSource(RecipeMetadata.SOURCE_AI_CREATE);
        meta.setRefId(creationId);
        meta.setSourceUrl(null);
        meta.setExtractorVersion("creation-v0.6");
        meta.setExtractorEngine("creation");
        meta.setExtractedAt(LocalDateTime.now());
        meta.setRawDigest(c.getSummary());
        meta.setStepsJson(c.getSteps());
        // 精确化评分：AI 创作主料无精确用量（如实低分），步骤含 timer_min
        List<Map<String, Object>> ingredients = parseStringIngredients(c.getMainIngredients(), c.getAuxiliaryIngredients());
        List<Map<String, Object>> steps = parseStepsJson(c.getSteps());
        boolean nutritionOk = c.getCalories() != null;
        boolean provenanceOk = true; // ref_id + creation 上下文即溯源完整
        BigDecimal score = precisionScoreService.compute(ingredients, steps, nutritionOk, provenanceOk);
        meta.setPrecisionScore(score);
        meta.setRecipeType(precisionScoreService.deriveType(score));
        meta.setIngredientsJson(writeJson(ingredients));
        meta.setNutritionJson(c.getCalories() != null ? "{\"calories\":" + c.getCalories() + "}" : null);
        if (isNew) {
            recipeMetadataRepository.insert(meta);
        } else {
            recipeMetadataRepository.updateById(meta);
        }
        provenanceService.record(meta);
        // 厨电参数：AI 创作镜像步骤含 温度/模式 → 按步骤派生（与流水线一致，供厨电联动）
        deviceCommandService.deriveDeviceParamsFromSteps(meta.getId(), steps);
        log.info("[酿味] AI 创作镜像入库 creationId={} recipeMetadataId={} type={} score={}",
                creationId, meta.getId(), meta.getRecipeType(), meta.getPrecisionScore());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("libraryRecipeId", meta.getId());
        r.put("sourceType", meta.getSourceType());
        r.put("creationId", creationId);
        return r;
    }

    private String join(String json) {
        return String.join("、", CreationJsonUtil.parseStringArray(json));
    }

    private List<Map<String, Object>> parseStringIngredients(String mainJson, String auxJson) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : CreationJsonUtil.parseStringArray(mainJson)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("is_main", 1);
            result.add(m);
        }
        for (String name : CreationJsonUtil.parseStringArray(auxJson)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("is_main", 0);
            result.add(m);
        }
        return result;
    }

    private List<Map<String, Object>> parseStepsJson(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(stepsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("AI 创作 steps 解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
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
}
