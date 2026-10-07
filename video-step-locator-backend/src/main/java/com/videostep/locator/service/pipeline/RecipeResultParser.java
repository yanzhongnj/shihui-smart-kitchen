package com.videostep.locator.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.DownloadTask;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.DownloadTaskRepository;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.PrecisionScoreService;
import com.videostep.locator.service.RecipeProvenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析流水线产出的 recipe_metadata.json 并写入统一菜谱主表 recipes（视频链路）。
 * 统一模型 v0.2：落 source/溯源/precision_score/ingredients|steps|nutrition 快照。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeResultParser {

    private final RecipeMetadataRepository recipeMetadataRepository;
    private final com.videostep.locator.repository.VideoRepository videoRepository;
    private final com.videostep.locator.service.device.DeviceCommandService deviceCommandService;
    private final DownloadTaskRepository downloadTaskRepository;
    private final RecipeIngredientRepository ingredientRepository;
    private final PrecisionScoreService precisionScoreService;
    private final RecipeProvenanceService provenanceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void saveRecipe(Long videoId, String videoPath, String outputDir) {
        try {
            // B 端：菜谱随视频归属企业（org_id 盖章）
            Long orgId = null;
            com.videostep.locator.entity.Video v = videoRepository.selectById(videoId);
            if (v != null) orgId = v.getOrgId();

            String videoFileName = Paths.get(videoPath).getFileName().toString();
            String stem = videoFileName.contains(".")
                    ? videoFileName.substring(0, videoFileName.lastIndexOf('.'))
                    : videoFileName;
            File recipeFile = new File(outputDir + "/recipe/" + stem + "_recipe.json");
            if (!recipeFile.exists()) {
                log.debug("菜谱结果文件不存在，跳过: {}", recipeFile);
                return;
            }
            Map<String, Object> data = objectMapper.readValue(recipeFile, Map.class);

            RecipeMetadata rm = RecipeMetadata.builder()
                    .videoId(videoId)
                    .orgId(orgId)
                    .title(asStr(data.get("title")))
                    .summary(asStr(data.get("summary")))
                    .mainIngredients(joinList(data.get("main_ingredients")))
                    .auxiliaryIngredients(joinList(data.get("auxiliary_ingredients")))
                    .tasteTags(joinList(data.get("taste_tags")))
                    .allergens(joinList(data.get("allergens")))
                    .spiceLevel(intOf(data.get("spice_level")))
                    .cuisine(asStr(data.get("cuisine")))
                    .estimatedTime(intOf(data.get("estimated_time")))
                    .calories(intOf(data.get("calories")))
                    .rawLlmJson(objectMapper.writeValueAsString(data))
                    .build();

            // ---- 统一模型 v0.2：来源 / 溯源 / 精确化评分 ----
            rm.setSource(RecipeMetadata.SOURCE_VIDEO_LINK);
            rm.setRefId(videoId);
            rm.setExtractorVersion("pipeline-v2.1");
            rm.setExtractorEngine("whisperx+deepseek");
            rm.setExtractedAt(LocalDateTime.now());
            rm.setRawDigest(asStr(data.get("summary")));
            rm.setServings(intOf(data.get("servings")));
            rm.setStepsJson(writeJson(data.get("steps")));

            // 溯源：视频来源需有 URL，从下载任务取源地址（无则如实置空，溯源完整度降低）
            DownloadTask dt = downloadTaskRepository.findFirstByVideoIdWithUrl(videoId);
            if (dt != null) {
                rm.setSourceUrl(dt.getSourceUrl() != null ? dt.getSourceUrl() : dt.getPageUrl());
            }

            // 精确化评分（§7.0）：主料精确率 / 步骤参数完整率 / 营养可算 / 溯源完整
            List<Map<String, Object>> ingredients = parseIngredients(data);
            List<Map<String, Object>> steps = parseSteps(data.get("steps"));
            boolean nutritionOk = rm.getCalories() != null;
            boolean provenanceOk = rm.getSourceUrl() != null;
            BigDecimal score = precisionScoreService.compute(ingredients, steps, nutritionOk, provenanceOk);
            rm.setPrecisionScore(score);
            rm.setRecipeType(precisionScoreService.deriveType(score));
            rm.setIngredientsJson(writeJson(ingredients));
            rm.setNutritionJson(rm.getCalories() != null ? "{\"calories\":" + rm.getCalories() + "}" : null);

            RecipeMetadata existing = recipeMetadataRepository.findByVideoId(videoId);
            if (existing != null) {
                rm.setId(existing.getId());
                recipeMetadataRepository.updateById(rm);
            } else {
                recipeMetadataRepository.insert(rm);
            }
            // 食材精确用量写入权威表 recipe_ingredients（幂等：先删后插）
            if (rm.getId() != null) {
                saveIngredients(rm.getId(), ingredients);
            }
            // 溯源流水（每次提取一条）
            provenanceService.record(rm);
            // A3 P1：流水线 device_params 解析入库（播放页按 step_index 匹配展示）
            if (rm.getId() != null) {
                deviceCommandService.saveDeviceParamsFromRecipe(rm.getId(), data);
            }
            log.info("视频 {} 菜谱元数据已保存 source={} type={} score={}", videoId,
                    rm.getSource(), rm.getRecipeType(), rm.getPrecisionScore());
        } catch (Exception e) {
            log.warn("保存菜谱失败 video {}: {}", videoId, e.getMessage());
        }
    }

    private String asStr(Object o) { return o == null ? null : o.toString(); }

    private Integer intOf(Object o) { return o instanceof Number n ? n.intValue() : null; }

    private String joinList(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object x : list) {
                if (x == null) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(x);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return o.toString();
    }

    /** 规范化食材：优先新格式 ingredients[]（含 amount/unit/tolerance/is_main）；兜底旧格式 main_ingredients 字符串列表（无精确用量，如实低分） */
    private List<Map<String, Object>> parseIngredients(Map<String, Object> data) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object ing = data.get("ingredients");
        if (ing instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> norm = new LinkedHashMap<>();
                for (String k : new String[]{"name", "amount", "unit", "tolerance", "is_main", "note"}) {
                    if (m.containsKey(k)) norm.put(k, m.get(k));
                }
                result.add(norm);
            }
        }
        if (result.isEmpty() && data.get("main_ingredients") instanceof List<?> mains) {
            for (Object o : mains) {
                Map<String, Object> norm = new LinkedHashMap<>();
                norm.put("name", o);
                norm.put("is_main", 1);
                result.add(norm);
            }
        }
        return result;
    }

    /** 规范化步骤：提取评分所需的 timer_min/timerMin/temperature/mode（其余字段保留） */
    private List<Map<String, Object>> parseSteps(Object stepsObj) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (stepsObj instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> norm = new LinkedHashMap<>();
                for (String k : new String[]{"index", "summary", "detail", "timer_min", "timerMin", "temperature", "mode"}) {
                    if (m.containsKey(k)) norm.put(k, m.get(k));
                }
                result.add(norm);
            }
        }
        return result;
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

    /** 写入食材精确用量（权威表 recipe_ingredients） */
    private void saveIngredients(Long recipeId, List<Map<String, Object>> ingredients) {
        try {
            ingredientRepository.deleteByRecipeId(recipeId);
            int seq = 1;
            for (Map<String, Object> ig : ingredients) {
                RecipeIngredient row = RecipeIngredient.builder()
                        .recipeId(recipeId)
                        .seq(seq++)
                        .name(asStr(ig.get("name")))
                        .amount(numOf(ig.get("amount")))
                        .unit(asStr(ig.get("unit")))
                        .tolerance(numOf(ig.get("tolerance")))
                        .isMain(isMainFlag(ig))
                        .note(asStr(ig.get("note")))
                        .build();
                ingredientRepository.insert(row);
            }
        } catch (Exception e) {
            log.warn("recipe_ingredients 写入失败 recipe={}: {}", recipeId, e.getMessage());
        }
    }

    private BigDecimal numOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = String.valueOf(o).trim();
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    private Integer isMainFlag(Map<String, Object> ig) {
        Object v = ig.get("is_main") != null ? ig.get("is_main") : ig.get("isMain");
        if (v == null) return 1;
        String s = String.valueOf(v).trim();
        return ("1".equals(s) || "true".equalsIgnoreCase(s)) ? 1 : 0;
    }
}
