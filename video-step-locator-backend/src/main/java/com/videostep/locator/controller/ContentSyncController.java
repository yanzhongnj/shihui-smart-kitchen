package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.ingredient.ContentSyncService;
import com.videostep.locator.service.ingredient.NutritionBackfillService;
import com.videostep.locator.service.ingredient.RecipeBatchUpgradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 开源内容源同步（P1，设计文档 §5/§7）+ 批量工业型升级 / 营养库回填增强（P 增强）。
 * run/upgrade-industrial/nutrition 仅管理员（@PreAuthorize ADMIN）；status/sources 登录可读。
 */
@RestController
@RequestMapping("/api/content-sync")
@RequiredArgsConstructor
@Slf4j
public class ContentSyncController {

    private final ContentSyncService contentSyncService;
    private final RecipeBatchUpgradeService batchUpgradeService;
    private final NutritionBackfillService nutritionBackfillService;

    /** 手动触发内容源同步（管理员）：?source=howtocook */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> run(@RequestParam(defaultValue = "howtocook") String source) {
        try {
            if (!"howtocook".equalsIgnoreCase(source)) {
                return ApiResponse.error(400, "暂不支持内容源: " + source);
            }
            return ApiResponse.success("内容同步已触发", contentSyncService.runHowToCook());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 同步状态/进度/最近结果 */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.success(contentSyncService.status());
    }

    /** 内容源列表（含来源 URL 溯源） */
    @GetMapping("/sources")
    public ApiResponse<List<Map<String, Object>>> sources() {
        return ApiResponse.success(contentSyncService.sources());
    }

    /**
     * 批量精确化升级（管理员）：对未达工业型的菜谱批量 LLM 精化（不计费）。
     * source 可选 howtocook（默认，HowToCook 导入）/ video（视频菜谱）/ all（全部）；
     * limit 可选限制本次处理数。
     */
    @PostMapping("/upgrade-industrial")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> upgradeIndustrial(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "howtocook") String source) {
        try {
            String s = source == null ? "howtocook" : source.toLowerCase();
            if (!java.util.List.of("howtocook", "video", "all").contains(s)) {
                return ApiResponse.error(400, "不支持的来源: " + source + "（可选 howtocook/video/all）");
            }
            return ApiResponse.success("批量精确化已执行", batchUpgradeService.run(limit, s));
        } catch (Exception e) {
            log.warn("批量精确化失败", e);
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 营养库缺失食材收集（管理员） */
    @PostMapping("/nutrition/collect")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> collectNutrition() {
        return ApiResponse.success(nutritionBackfillService.collectMissing());
    }

    /** 营养库回填（管理员）：收集缺失 → AI 估算 → 入库（source=ai）；dryRun=true 仅返回候选 */
    @PostMapping("/nutrition/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> backfillNutrition(
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(required = false) Integer max) {
        try {
            return ApiResponse.success("营养库回填已执行", nutritionBackfillService.backfill(dryRun, max));
        } catch (Exception e) {
            log.warn("营养库回填失败", e);
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
