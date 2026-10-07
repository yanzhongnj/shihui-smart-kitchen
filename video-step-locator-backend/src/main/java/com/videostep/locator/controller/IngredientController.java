package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.FoodAlias;
import com.videostep.locator.service.ingredient.FoodAliasService;
import com.videostep.locator.service.ingredient.IngredientIndexService;
import com.videostep.locator.service.ingredient.PhotoLookupService;
import com.videostep.locator.service.ingredient.ReverseLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 食材反查 P0：标签云 + 组合反查 + 同义词管理。
 * 设计文档《拾味_食材反查与内容源_技术设计文档.md》§7。
 * /api/ingredients/** 默认走 JWT（SecurityConfig /api/** authenticated）。
 */
@RestController
@RequestMapping("/api/ingredients")
@RequiredArgsConstructor
@Slf4j
public class IngredientController {

    private final IngredientIndexService indexService;
    private final ReverseLookupService reverseLookupService;
    private final FoodAliasService foodAliasService;
    private final PhotoLookupService photoLookupService;

    /** 食材标签云（按频次倒序；keyword 过滤） */
    @GetMapping("/tags")
    public ApiResponse<Map<String, Object>> tags(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String keyword) {
        indexService.ensureIndex();
        List<Map<String, Object>> items = indexService.tags(limit, keyword);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", items.size());
        return ApiResponse.success(data);
    }

    /** 手动重建标签索引（管理端，ADMIN） */
    @PostMapping("/tags/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> rebuild() {
        int n = indexService.rebuild();
        return ApiResponse.success("已重建食材标签索引", Map.of("tags", n));
    }

    /** 食材反查：items=鸡腿,土豆&mode=all|missing-one&sort=like|calorie|difficulty&maxTime=分钟&difficulty=simple|medium|hard */
    @GetMapping("/reverse")
    public ApiResponse<Map<String, Object>> reverse(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam String items,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer maxTime,
            @RequestParam(required = false) String difficulty) {
        try {
            indexService.ensureIndex();
            List<String> itemList = Arrays.stream(items.split("[,，]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (itemList.isEmpty()) return ApiResponse.error(400, "请至少选择一个食材");
            return ApiResponse.success(reverseLookupService.reverse(itemList, mode, sort, maxTime, difficulty, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 拍照反查（P1）：识别食材 → 自动反查（复用 XunweiVisionService 识菜 + ReverseLookupService） */
    @PostMapping("/photo-lookup")
    public ApiResponse<Map<String, Object>> photoLookup(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam("file") MultipartFile file) {
        try {
            indexService.ensureIndex();
            Map<String, Object> data = photoLookupService.photoLookup(
                    file.getBytes(), file.getOriginalFilename(), userId);
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.warn("拍照反查失败", e);
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 同义词列表（管理端，ADMIN） */
    @GetMapping("/aliases")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> aliases() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FoodAlias a : foodAliasService.list()) {
            out.add(aliasMap(a));
        }
        return ApiResponse.success(out);
    }

    /** 新增同义词：body {alias, canonical, source?}（管理端，ADMIN） */
    @PostMapping("/aliases")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> createAlias(@RequestBody Map<String, Object> body) {
        try {
            String alias = body.get("alias") instanceof String s ? s : null;
            String canonical = body.get("canonical") instanceof String s ? s : null;
            String source = body.get("source") instanceof String s ? s : null;
            FoodAlias fa = foodAliasService.create(alias, canonical, source);
            return ApiResponse.success("已新增同义词", aliasMap(fa));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 删除同义词（管理端，ADMIN） */
    @DeleteMapping("/aliases/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> deleteAlias(@PathVariable Long id) {
        try {
            foodAliasService.deleteAlias(id);
            return ApiResponse.success("已删除同义词", "ok");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    private Map<String, Object> aliasMap(FoodAlias a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("alias", a.getAlias());
        m.put("canonical", a.getCanonical());
        m.put("source", a.getSource());
        return m;
    }
}
