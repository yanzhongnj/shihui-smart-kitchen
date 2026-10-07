package com.videostep.locator.service.ingredient;

import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.RecipeUpgradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * HowToCook 导入菜谱的批量工业型升级（P 增强，设计《拾味_菜谱来源体系与工业级精确化》§6.1）。
 * 遍历已导入的 howtocook 菜谱（未达 INDUSTRIAL）→ 复用 RecipeUpgradeService.upgradeNoBilling（LLM 精化，不计费）
 * → 重算 precision_score / recipe_type；带速率间隔 + 失败容错（单条失败不中断，失败保留其余继续）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeBatchUpgradeService {

    private final RecipeMetadataRepository recipeRepository;
    private final RecipeUpgradeService upgradeService;

    @Value("${content-sync.howtocook.upgrade-delay-ms:200}")
    private long upgradeDelayMs;

    @Value("${content-sync.howtocook.upgrade-max:500}")
    private int upgradeMax;

    /** 本进程已尝试过的菜谱（可中断分批续跑，避免重复烧 token） */
    private final Set<Long> attempted = ConcurrentHashMap.newKeySet();

    public Map<String, Object> run(Integer limitOverride) {
        return run(limitOverride, "howtocook");
    }

    /**
     * 批量精确化（LLM 精化，不计费）。source 取值：
     *   howtocook（默认）→ 仅 HowToCook 导入菜谱（extractorVersion=howtocook-md-v1，原行为）
     *   video            → 视频菜谱（source=VIDEO_LINK）
     *   all              → 全部未达工业型的菜谱
     */
    public Map<String, Object> run(Integer limitOverride, String source) {
        List<RecipeMetadata> targets = recipeRepository.selectList(null).stream()
                .filter(r -> !RecipeMetadata.TYPE_INDUSTRIAL.equals(r.getRecipeType()))
                .filter(r -> !attempted.contains(r.getId()))
                .filter(r -> matchesSource(r, source))
                .collect(Collectors.toList());
        int limit = Math.min(targets.size(),
                limitOverride != null && limitOverride > 0 ? limitOverride : upgradeMax);

        int upgraded = 0;      // 处理成功（含升级后仍 HOME）
        int industrial = 0;    // 升级后达到工业型
        int failed = 0;
        List<String> errors = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (int i = 0; i < limit; i++) {
            RecipeMetadata r = targets.get(i);
            attempted.add(r.getId());
            if (i > 0 && upgradeDelayMs > 0) sleep(upgradeDelayMs);
            try {
                Map<String, Object> res = upgradeService.upgradeNoBilling(r.getId());
                upgraded++;
                if (RecipeMetadata.TYPE_INDUSTRIAL.equals(res.get("recipeType"))) industrial++;
            } catch (Exception e) {
                failed++;
                if (errors.size() < 5) errors.add(r.getId() + ":" + e.getMessage());
                log.warn("批量升级失败 recipe {}: {}", r.getId(), e.getMessage());
            }
        }
        long costMs = System.currentTimeMillis() - start;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("target", targets.size());
        res.put("processed", limit);
        res.put("upgraded", upgraded);
        res.put("industrial", industrial);
        res.put("stillHome", upgraded - industrial);
        res.put("failed", failed);
        res.put("remaining", targets.size() - limit);
        res.put("costMs", costMs);
        res.put("errors", errors);
        log.info("批量精确化升级完成：source={} target={} processed={} upgraded={} industrial={} failed={} remaining={} costMs={}",
                source, targets.size(), limit, upgraded, industrial, failed, targets.size() - limit, costMs);
        return res;
    }

    private boolean matchesSource(RecipeMetadata r, String source) {
        if (source == null || "howtocook".equalsIgnoreCase(source)) {
            return "howtocook-md-v1".equals(r.getExtractorVersion());
        }
        if ("video".equalsIgnoreCase(source)) {
            return RecipeMetadata.SOURCE_VIDEO_LINK.equals(r.getSource());
        }
        if ("all".equalsIgnoreCase(source)) {
            return true;
        }
        return false; // 未知来源不处理
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
