package com.videostep.locator.service.xunwei;

import com.videostep.locator.entity.XunweiMessage;
import com.videostep.locator.entity.XunweiSession;
import com.videostep.locator.repository.XunweiMessageRepository;
import com.videostep.locator.repository.XunweiSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「寻味」会话 / 消息管理（CRUD + 属主校验）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class XunweiSessionService {

    private final XunweiSessionRepository sessionRepository;
    private final XunweiMessageRepository messageRepository;

    public XunweiSession createSession(Long userId, String title) {
        XunweiSession s = XunweiSession.builder()
                .userId(userId)
                .title(title == null || title.isBlank() ? "新对话" : title.trim())
                .build();
        sessionRepository.insert(s);
        return s;
    }

    /** 属主校验：会话不存在或非本人所有时抛异常 */
    public XunweiSession getOwnedSession(Long userId, Long sessionId) {
        XunweiSession s = sessionRepository.selectById(sessionId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        return s;
    }

    public List<Map<String, Object>> listSessions(Long userId) {
        List<XunweiSession> sessions = sessionRepository.findByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (XunweiSession s : sessions) {
            XunweiMessage last = messageRepository.findLastBySessionId(s.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("lastPreview", last != null ? truncate(last.getContent(), 50) : "");
            m.put("messageCount", messageRepository.countBySessionId(s.getId()));
            m.put("updatedAt", s.getUpdatedAt());
            result.add(m);
        }
        return result;
    }

    public void renameSession(Long userId, Long sessionId, String title) {
        XunweiSession s = getOwnedSession(userId, sessionId);
        s.setTitle(title == null || title.isBlank() ? "未命名对话" : title.trim());
        sessionRepository.updateById(s);
    }

    public void deleteSession(Long userId, Long sessionId) {
        XunweiSession s = getOwnedSession(userId, sessionId);
        // 消息由外键 ON DELETE CASCADE 一并删除
        sessionRepository.deleteById(s.getId());
        log.info("[寻味] 删除会话 userId={} sessionId={}", userId, sessionId);
    }

    /** 更新会话滚动摘要（超出窗口的历史压缩结果） */
    public void updateSummary(Long userId, Long sessionId, String summary) {
        XunweiSession s = getOwnedSession(userId, sessionId);
        s.setSummary(summary);
        sessionRepository.updateById(s);
    }

    public List<XunweiMessage> getMessages(Long userId, Long sessionId) {
        getOwnedSession(userId, sessionId);
        return messageRepository.findBySessionId(sessionId);
    }

    public void addMessage(Long sessionId, String role, String content, String videoJson) {
        XunweiMessage m = XunweiMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .videoJson(videoJson)
                .build();
        messageRepository.insert(m);
        // 触达 updated_at（ON UPDATE CURRENT_TIMESTAMP 在无字段变化时不生效，这里显式更新）
        XunweiSession touch = new XunweiSession();
        touch.setId(sessionId);
        sessionRepository.updateById(touch);
    }

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
