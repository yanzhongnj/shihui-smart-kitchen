package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.service.ArticleFetcherService;
import com.videostep.locator.service.ArticleRecipeExtractor;
import com.videostep.locator.service.RecipeProvenanceService;
import com.videostep.locator.service.RecipeScaleService;
import com.videostep.locator.service.RecipeService;
import com.videostep.locator.service.RecipeUpgradeService;
import com.videostep.locator.service.device.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜谱列表/详情/兼容性/录入/换算/精确化增强/图文解析。
 * 列表/详情/换算游客可只读访问（无 userId）；录入/增强/图文解析需登录。
 */
@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
@Slf4j
public class RecipeController {

    private final RecipeService recipeService;
    private final RecipeScaleService recipeScaleService;
    private final RecipeUpgradeService recipeUpgradeService;
    private final RecipeProvenanceService recipeProvenanceService;
    private final ArticleFetcherService articleFetcherService;
    private final ArticleRecipeExtractor articleRecipeExtractor;
    private final DeviceCommandService deviceCommandService;

    @GetMapping
    public ApiResponse<?> list(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam(required = false) String cuisine,
            @RequestParam(required = false) String taste,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) BigDecimal minScore,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        // 分页（Web 管理页）：传 page/size 返回 {items,total,page,size,totalPages}；
        // 不传则返回平铺数组（兼容 APP/游客 只读列表，避免破坏移动端分页体验）
        if (page != null && size != null) {
            return ApiResponse.success(
                    recipeService.listPaged(cuisine, taste, source, type, minScore, userId, page, size));
        }
        return ApiResponse.success(recipeService.list(cuisine, taste, source, type, minScore, userId));
    }

    /** USER 来源录入菜谱（需登录；工业型强校验见 RecipeValidationService） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestBody Map<String, Object> body) {
        if (userId == null) return ApiResponse.error(401, "请登录后录入菜谱");
        return ApiResponse.success(recipeService.createUserRecipe(userId, body));
    }

    /** 修改菜谱（创建者或管理员；保留来源/溯源，重算评分/类型并重存食材/步骤） */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestBody Map<String, Object> body) {
        if (userId == null) return ApiResponse.error(401, "请登录后修改菜谱");
        return ApiResponse.success(recipeService.updateRecipe(userId, id, body));
    }

    /** 链接图文解析（ARTICLE_LINK，需登录）：抓取正文 → LLM 结构化草稿（不落库，预览后调 create 保存） */
    @PostMapping("/parse-article")
    public ApiResponse<Map<String, Object>> parseArticle(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestBody Map<String, Object> body) {
        if (userId == null) return ApiResponse.error(401, "请登录后解析图文菜谱");
        String url = body.get("url") == null ? null : String.valueOf(body.get("url"));
        if (url == null || url.isBlank()) return ApiResponse.error(400, "请提供文章链接");
        Map<String, Object> fetched = articleFetcherService.fetch(url);
        Map<String, Object> draft = articleRecipeExtractor.extract(url,
                fetched.get("title") == null ? null : String.valueOf(fetched.get("title")),
                fetched.get("text") == null ? null : String.valueOf(fetched.get("text")));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("source", "ARTICLE_LINK");
        res.put("sourceUrl", url);
        res.put("draft", draft);
        return ApiResponse.success(res);
    }

    /** 分量换算 + 营养（游客可读可见菜谱）；mode=people(按人数，默认) | mold(按模具面积) */
    @GetMapping("/{id}/scale")
    public ApiResponse<Map<String, Object>> scale(
            @PathVariable Long id,
            @RequestParam(required = false) Integer servings,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String shape,
            @RequestParam(required = false) Double size1,
            @RequestParam(required = false) Double size2,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        RecipeMetadata r = recipeService.requireVisible(id, userId);
        if ("mold".equalsIgnoreCase(mode)) {
            return ApiResponse.success(recipeScaleService.scaleByMold(r, shape, size1, size2));
        }
        return ApiResponse.success(recipeScaleService.scale(r, servings));
    }

    /** 精确化评分分布（阈值校准参考） */
    @GetMapping("/precision-stats")
    public ApiResponse<Map<String, Object>> precisionStats() {
        return ApiResponse.success(recipeService.precisionStats());
    }

    /** 溯源详情：当前来源 + 历史流水（游客可读可见菜谱） */
    @GetMapping("/{id}/provenance")
    public ApiResponse<Map<String, Object>> provenance(
            @PathVariable Long id,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        RecipeMetadata r = recipeService.requireVisible(id, userId);
        return ApiResponse.success(recipeProvenanceService.getProvenance(r));
    }

    /** 菜谱设备参数一键下发（需登录；按类型自动找已绑定设备，无则友好提示） */
    @PostMapping("/{id}/device-params/{paramId}/send")
    public ApiResponse<Map<String, Object>> sendDeviceParam(
            @PathVariable Long id,
            @PathVariable Long paramId,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        if (userId == null) return ApiResponse.error(401, "请登录后下发厨电参数");
        recipeService.requireVisible(id, userId);
        return ApiResponse.success(deviceCommandService.sendRecipeParam(userId, id, paramId));
    }

    /** 精确化增强（需登录 + 计费；LLM 精化后重算 precision_score） */
    @PostMapping("/{id}/upgrade")
    public ApiResponse<Map<String, Object>> upgrade(
            @PathVariable Long id,
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        if (userId == null) return ApiResponse.error(401, "请登录后使用精确化增强");
        recipeService.requireVisible(id, userId);
        return ApiResponse.success(recipeUpgradeService.upgrade(userId, id, deviceId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable Long id,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ApiResponse.success(recipeService.detail(id, userId));
    }

    /** 删除菜谱（创建者或管理员）；先删子表再删主表 */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(
            @PathVariable Long id,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        if (userId == null) return ApiResponse.error(401, "请登录后删除菜谱");
        recipeService.deleteRecipe(id, userId);
        return ApiResponse.success(Map.of("deleted", true));
    }

    @GetMapping("/{id}/compatibility")
    public ApiResponse<Map<String, Object>> compatibility(
            @PathVariable Long id,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        if (userId == null) {
            return ApiResponse.error(401, "请登录后查看口味匹配");
        }
        return ApiResponse.success(recipeService.compatibility(id, userId));
    }
}
