package com.videostep.locator.service;

import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.RecipeSource;
import com.videostep.locator.repository.RecipeSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜谱溯源（recipe_sources 流水，设计文档 §5.2 表3 / §8 溯源详情）。
 * 每次提取/录入/升级/衍生写一条流水；溯源详情 = 当前（主表内嵌）+ 历史流水（倒序）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeProvenanceService {

    private final RecipeSourceRepository sourceRepository;

    /** 记录一条来源流水（写入后调用，幂等性由调用方保证） */
    public void record(RecipeMetadata r) {
        record(r, null);
    }

    /** 记录一条来源流水；versionOverride 用于标注事件类型（如升级=upgrade-llm） */
    public void record(RecipeMetadata r, String versionOverride) {
        if (r == null || r.getId() == null) return;
        RecipeSource s = RecipeSource.builder()
                .recipeId(r.getId())
                .source(r.getSource())
                .sourceUrl(r.getSourceUrl())
                .refId(r.getRefId())
                .extractorVersion(versionOverride != null ? versionOverride : r.getExtractorVersion())
                .extractorEngine(r.getExtractorEngine())
                .rawDigest(r.getRawDigest())
                .confidence(r.getConfidence())
                .extractedAt(r.getExtractedAt() != null ? r.getExtractedAt() : LocalDateTime.now())
                .build();
        try {
            sourceRepository.insert(s);
        } catch (Exception e) {
            log.warn("溯源流水写入失败 recipe={}: {}", r.getId(), e.getMessage());
        }
    }

    /** 溯源详情：当前（主表内嵌）+ 历史流水 */
    public Map<String, Object> getProvenance(RecipeMetadata r) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("recipeId", r.getId());
        res.put("title", r.getTitle());
        res.put("source", r.getSource());
        res.put("sourceUrl", r.getSourceUrl());
        res.put("refId", r.getRefId());
        res.put("extractorVersion", r.getExtractorVersion());
        res.put("extractorEngine", r.getExtractorEngine());
        res.put("extractedAt", r.getExtractedAt());
        res.put("rawDigest", r.getRawDigest());
        res.put("confidence", r.getConfidence());
        List<Map<String, Object>> history = new ArrayList<>();
        for (RecipeSource rs : sourceRepository.findByRecipeId(r.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getId());
            m.put("source", rs.getSource());
            m.put("sourceUrl", rs.getSourceUrl());
            m.put("refId", rs.getRefId());
            m.put("extractorVersion", rs.getExtractorVersion());
            m.put("extractorEngine", rs.getExtractorEngine());
            m.put("extractedAt", rs.getExtractedAt());
            history.add(m);
        }
        res.put("history", history);
        return res;
    }
}
