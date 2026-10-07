package com.videostep.locator.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.FoodSafetyRule;
import com.videostep.locator.entity.FoodSafetyRuleCandidate;
import com.videostep.locator.entity.FoodSafetyRuleFeedback;
import com.videostep.locator.repository.FoodSafetyRuleCandidateRepository;
import com.videostep.locator.repository.FoodSafetyRuleFeedbackRepository;
import com.videostep.locator.repository.FoodSafetyRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 食物安全规则运营管理（运营增强 D5，仅 ADMIN）。
 * 覆盖：候选审核队列（LLM 提取候选 → 通过入库/驳回）、规则反馈查看与无用率统计。
 */
@RestController
@RequestMapping("/api/admin/safety")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminSafetyController {

    private final FoodSafetyRuleCandidateRepository candidateRepository;
    private final FoodSafetyRuleRepository ruleRepository;
    private final FoodSafetyRuleFeedbackRepository feedbackRepository;

    // ---------------- 候选审核队列 ----------------

    @GetMapping("/candidates")
    public ApiResponse<Map<String, Object>> candidates(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LambdaQueryWrapper<FoodSafetyRuleCandidate> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) qw.eq(FoodSafetyRuleCandidate::getStatus, status);
        qw.orderByAsc(FoodSafetyRuleCandidate::getId);
        long total = candidateRepository.selectCount(qw);
        int p = Math.max(0, page), s = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> items = candidateRepository
                .selectList(qw.last("LIMIT " + p * s + "," + s)).stream()
                .map(this::candidateMap).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("total", total);
        r.put("page", p);
        r.put("size", s);
        return ApiResponse.success(r);
    }

    /** 通过候选 → 入库 food_safety_rules（title 重复则拒绝） */
    @PostMapping("/candidates/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        FoodSafetyRuleCandidate c = candidateRepository.selectById(id);
        if (c == null) throw new IllegalArgumentException("候选不存在");
        if ("approved".equals(c.getStatus())) throw new IllegalArgumentException("候选已审核通过");
        Long dup = ruleRepository.selectCount(new LambdaQueryWrapper<FoodSafetyRule>()
                .eq(FoodSafetyRule::getTitle, c.getTitle()));
        if (dup > 0) throw new IllegalArgumentException("规则已存在（标题重复）");
        FoodSafetyRule rule = FoodSafetyRule.builder()
                .category(c.getCategory())
                .riskLevel(c.getRiskLevel())
                .triggerType(c.getTriggerType())
                .triggerValue(c.getTriggerValue())
                .relatedFood(c.getRelatedFood())
                .title(c.getTitle())
                .advice(c.getAdvice())
                .source(c.getSource())
                .sourceUrl(c.getSourceUrl())
                .confidence(c.getConfidence())
                .version(1)
                .enabled(1)
                .deleted(0)
                .build();
        ruleRepository.insert(rule);
        c.setStatus("approved");
        c.setReviewedAt(LocalDateTime.now());
        c.setReviewedBy(userId);
        candidateRepository.updateById(c);
        log.info("safety candidate {} approved → rule {}", id, rule.getId());
        return ApiResponse.success(Map.of("ruleId", rule.getId()));
    }

    @PostMapping("/candidates/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        FoodSafetyRuleCandidate c = candidateRepository.selectById(id);
        if (c == null) throw new IllegalArgumentException("候选不存在");
        c.setStatus("rejected");
        c.setReviewedAt(LocalDateTime.now());
        c.setReviewedBy(userId);
        candidateRepository.updateById(c);
        return ApiResponse.success(Map.of("rejected", true));
    }

    // ---------------- 反馈查看与统计 ----------------

    @GetMapping("/feedback")
    public ApiResponse<Map<String, Object>> feedback(
            @RequestParam(required = false) Long ruleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LambdaQueryWrapper<FoodSafetyRuleFeedback> qw = new LambdaQueryWrapper<>();
        if (ruleId != null) qw.eq(FoodSafetyRuleFeedback::getRuleId, ruleId);
        qw.orderByDesc(FoodSafetyRuleFeedback::getId);
        long total = feedbackRepository.selectCount(qw);
        int p = Math.max(0, page), s = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> items = feedbackRepository
                .selectList(qw.last("LIMIT " + p * s + "," + s)).stream()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", f.getId());
                    m.put("ruleId", f.getRuleId());
                    m.put("userId", f.getUserId());
                    m.put("useful", f.getUseful());
                    m.put("comment", f.getComment());
                    m.put("createdAt", f.getCreatedAt());
                    return m;
                }).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("total", total);
        return ApiResponse.success(r);
    }

    /** 各规则无用率统计（降序） */
    @GetMapping("/feedback/summary")
    public ApiResponse<List<Map<String, Object>>> feedbackSummary() {
        Map<Long, List<FoodSafetyRuleFeedback>> byRule = feedbackRepository.selectList(null).stream()
                .collect(Collectors.groupingBy(FoodSafetyRuleFeedback::getRuleId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : byRule.entrySet()) {
            long total = e.getValue().size();
            long useless = e.getValue().stream()
                    .filter(f -> f.getUseful() != null && f.getUseful() == 0).count();
            FoodSafetyRule rule = ruleRepository.selectById(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", e.getKey());
            m.put("title", rule != null ? rule.getTitle() : "（已删除）");
            m.put("category", rule != null ? rule.getCategory() : null);
            m.put("level", rule != null ? rule.getRiskLevel() : null);
            m.put("enabled", rule != null ? rule.getEnabled() : 0);
            m.put("total", total);
            m.put("useless", useless);
            m.put("uselessRate", Math.round(useless * 1000.0 / total) / 10.0);
            out.add(m);
        }
        out.sort((a, b) -> Double.compare(
                ((Number) b.get("uselessRate")).doubleValue(),
                ((Number) a.get("uselessRate")).doubleValue()));
        return ApiResponse.success(out);
    }

    private Map<String, Object> candidateMap(FoodSafetyRuleCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("category", c.getCategory());
        m.put("riskLevel", c.getRiskLevel());
        m.put("triggerType", c.getTriggerType());
        m.put("triggerValue", c.getTriggerValue());
        m.put("relatedFood", c.getRelatedFood());
        m.put("title", c.getTitle());
        m.put("advice", c.getAdvice());
        m.put("source", c.getSource());
        m.put("sourceUrl", c.getSourceUrl());
        m.put("status", c.getStatus());
        m.put("confidence", c.getConfidence());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }
}
