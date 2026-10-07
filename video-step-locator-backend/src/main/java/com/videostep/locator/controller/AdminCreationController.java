package com.videostep.locator.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiCreationMessage;
import com.videostep.locator.entity.AiCreationSession;
import com.videostep.locator.repository.AiCreationMessageRepository;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.repository.AiCreationSessionRepository;
import com.videostep.locator.service.UserService;
import com.videostep.locator.service.creation.CreationSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * M3：酿味 Web 管理（仅 ADMIN）。统一 /api/admin/creation/**
 *
 * <p>跨用户会话/创作列表、消息查看、删除；直接查询 repository（新增代码），
 * 删除复用 CreationSessionService.deleteCreationWithFiles（无属主校验，仅按 id 清理），
 * 不改动现有 CreationController / APP 在用接口逻辑。</p>
 */
@RestController
@RequestMapping("/api/admin/creation")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminCreationController {

    private final AiCreationSessionRepository sessionRepository;
    private final AiCreationMessageRepository messageRepository;
    private final AiCreationRepository creationRepository;
    private final CreationSessionService creationSessionService;
    private final UserService userService;

    // ---------------- 酿味会话 ----------------

    /** 全量酿味会话（分页 + userId/标题/状态筛选） */
    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> sessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<AiCreationSession> qw = new LambdaQueryWrapper<AiCreationSession>()
                .eq(userId != null, AiCreationSession::getUserId, userId)
                .eq(status != null && !status.isBlank(), AiCreationSession::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            qw.like(AiCreationSession::getTitle, keyword);
        }
        qw.orderByDesc(AiCreationSession::getUpdatedAt);
        long total = sessionRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = sessionRepository.selectList(qw).stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("userId", s.getUserId());
                    m.put("username", username(s.getUserId()));
                    m.put("title", s.getTitle());
                    m.put("status", s.getStatus());
                    m.put("messageCount", messageRepository.countBySessionId(s.getId()));
                    m.put("createdAt", s.getCreatedAt());
                    m.put("updatedAt", s.getUpdatedAt());
                    return m;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    /** 会话消息列表（含意图/附件 JSON） */
    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<Map<String, Object>>> messages(@PathVariable Long id) {
        AiCreationSession s = sessionRepository.selectById(id);
        if (s == null) return ApiResponse.error(404, "会话不存在");
        return ApiResponse.success(messageRepository.findBySessionId(id).stream()
                .map(this::msgMap)
                .collect(Collectors.toList()));
    }

    /** 删除会话（含关联创作与磁盘文件 + 消息） */
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Map<String, Object>> deleteSession(@PathVariable Long id) {
        AiCreationSession s = sessionRepository.selectById(id);
        if (s == null) return ApiResponse.error(404, "会话不存在");
        AiCreation c = creationRepository.findBySessionId(id);
        if (c != null) {
            creationSessionService.deleteCreationWithFiles(c.getId());
        }
        messageRepository.delete(new LambdaQueryWrapper<AiCreationMessage>()
                .eq(AiCreationMessage::getSessionId, id));
        sessionRepository.deleteById(id);
        log.info("[admin] 删除酿味会话 sessionId={} userId={} creationId={}",
                id, s.getUserId(), c == null ? null : c.getId());
        return ApiResponse.success(Map.of("deleted", true));
    }

    // ---------------- 酿味创作 ----------------

    /** 全量创作（分页 + userId/标题/状态筛选） */
    @GetMapping("/creations")
    public ApiResponse<Map<String, Object>> creations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<AiCreation> qw = new LambdaQueryWrapper<AiCreation>()
                .eq(userId != null, AiCreation::getUserId, userId)
                .eq(status != null && !status.isBlank(), AiCreation::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            qw.like(AiCreation::getTitle, keyword);
        }
        qw.orderByDesc(AiCreation::getUpdatedAt);
        long total = creationRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = creationRepository.selectList(qw).stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("userId", c.getUserId());
                    m.put("username", username(c.getUserId()));
                    m.put("title", c.getTitle());
                    m.put("inspiration", c.getInspiration());
                    m.put("status", c.getStatus());
                    m.put("version", c.getVersion());
                    m.put("iterCount", c.getIterCount());
                    m.put("sessionId", c.getSessionId());
                    m.put("calories", c.getCalories());
                    m.put("difficulty", c.getDifficulty());
                    m.put("stepCount", stepCount(c.getSteps()));
                    m.put("createdAt", c.getCreatedAt());
                    m.put("updatedAt", c.getUpdatedAt());
                    return m;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    /** 创作详情 */
    @GetMapping("/creations/{id}")
    public ApiResponse<Map<String, Object>> creationDetail(@PathVariable Long id) {
        AiCreation c = creationRepository.selectById(id);
        if (c == null) return ApiResponse.error(404, "创作不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("userId", c.getUserId());
        m.put("username", username(c.getUserId()));
        m.put("title", c.getTitle());
        m.put("summary", c.getSummary());
        m.put("inspiration", c.getInspiration());
        m.put("mainIngredients", c.getMainIngredients());
        m.put("auxiliaryIngredients", c.getAuxiliaryIngredients());
        m.put("tasteTags", c.getTasteTags());
        m.put("allergens", c.getAllergens());
        m.put("spiceLevel", c.getSpiceLevel());
        m.put("calories", c.getCalories());
        m.put("estimatedTime", c.getEstimatedTime());
        m.put("difficulty", c.getDifficulty());
        m.put("steps", c.getSteps());
        m.put("stepCount", stepCount(c.getSteps()));
        m.put("deviceParams", c.getDeviceParams());
        m.put("sourceType", c.getSourceType());
        m.put("creationId", c.getCreationId());
        m.put("sessionId", c.getSessionId());
        m.put("version", c.getVersion());
        m.put("iterCount", c.getIterCount());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return ApiResponse.success(m);
    }

    /** 删除创作（含磁盘文件与关联表） */
    @DeleteMapping("/creations/{id}")
    public ApiResponse<Map<String, Object>> deleteCreation(@PathVariable Long id) {
        AiCreation c = creationRepository.selectById(id);
        if (c == null) return ApiResponse.error(404, "创作不存在");
        creationSessionService.deleteCreationWithFiles(id);
        log.info("[admin] 删除酿味创作 creationId={} userId={}", id, c.getUserId());
        return ApiResponse.success(Map.of("deleted", true));
    }

    private Map<String, Object> msgMap(AiCreationMessage m) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", m.getId());
        r.put("role", m.getRole());
        r.put("content", m.getContent());
        r.put("attachments", m.getAttachments());
        r.put("intentJson", m.getIntentJson());
        r.put("createdAt", m.getCreatedAt());
        return r;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private int stepCount(String steps) {
        if (steps == null || steps.isBlank()) return 0;
        try {
            com.fasterxml.jackson.databind.JsonNode node = OM.readTree(steps);
            return node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String username(Long userId) {
        if (userId == null) return null;
        try { return userService.getById(userId).getUsername(); }
        catch (Exception e) { return null; }
    }
}
