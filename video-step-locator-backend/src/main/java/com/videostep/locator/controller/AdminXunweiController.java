package com.videostep.locator.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.XunweiMessage;
import com.videostep.locator.entity.XunweiSession;
import com.videostep.locator.repository.XunweiMessageRepository;
import com.videostep.locator.repository.XunweiSessionRepository;
import com.videostep.locator.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * M3：寻味会话 Web 管理（仅 ADMIN）。统一 /api/admin/xunwei/**
 *
 * <p>跨用户会话列表/消息查看/删除；直接查询 repository（新增代码），
 * 不改动现有 XunweiSessionService / APP 在用接口逻辑。</p>
 */
@RestController
@RequestMapping("/api/admin/xunwei")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminXunweiController {

    private final XunweiSessionRepository sessionRepository;
    private final XunweiMessageRepository messageRepository;
    private final UserService userService;

    /** 全量寻味会话（分页 + userId/标题关键字筛选） */
    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> sessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<XunweiSession> qw = new LambdaQueryWrapper<XunweiSession>()
                .eq(userId != null, XunweiSession::getUserId, userId);
        if (keyword != null && !keyword.isBlank()) {
            qw.like(XunweiSession::getTitle, keyword);
        }
        qw.orderByDesc(XunweiSession::getUpdatedAt);
        long total = sessionRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = sessionRepository.selectList(qw).stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("userId", s.getUserId());
                    m.put("username", username(s.getUserId()));
                    m.put("title", s.getTitle());
                    m.put("messageCount", messageRepository.countBySessionId(s.getId()));
                    m.put("createdAt", s.getCreatedAt());
                    m.put("updatedAt", s.getUpdatedAt());
                    return m;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    /** 会话消息列表 */
    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<Map<String, Object>>> messages(@PathVariable Long id) {
        XunweiSession s = sessionRepository.selectById(id);
        if (s == null) return ApiResponse.error(404, "会话不存在");
        return ApiResponse.success(messageRepository.findBySessionId(id).stream()
                .map(this::msgMap)
                .collect(Collectors.toList()));
    }

    /** 删除会话（含其全部消息） */
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Map<String, Object>> deleteSession(@PathVariable Long id) {
        XunweiSession s = sessionRepository.selectById(id);
        if (s == null) return ApiResponse.error(404, "会话不存在");
        messageRepository.delete(new LambdaQueryWrapper<XunweiMessage>()
                .eq(XunweiMessage::getSessionId, id));
        sessionRepository.deleteById(id);
        log.info("[admin] 删除寻味会话 sessionId={} userId={}", id, s.getUserId());
        return ApiResponse.success(Map.of("deleted", true));
    }

    private Map<String, Object> msgMap(XunweiMessage m) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", m.getId());
        r.put("role", m.getRole());
        r.put("content", m.getContent());
        r.put("createdAt", m.getCreatedAt());
        return r;
    }

    private String username(Long userId) {
        if (userId == null) return null;
        try { return userService.getById(userId).getUsername(); }
        catch (Exception e) { return null; }
    }
}
