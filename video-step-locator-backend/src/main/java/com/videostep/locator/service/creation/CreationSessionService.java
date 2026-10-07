package com.videostep.locator.service.creation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiCreationFeedback;
import com.videostep.locator.entity.AiCreationMessage;
import com.videostep.locator.entity.AiCreationSession;
import com.videostep.locator.entity.AiCreationShare;
import com.videostep.locator.entity.AiCreationVersion;
import com.videostep.locator.entity.AiCreationVideo;
import com.videostep.locator.entity.AiVideoTask;
import com.videostep.locator.repository.AiCreationFeedbackRepository;
import com.videostep.locator.repository.AiCreationMessageRepository;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.repository.AiCreationSessionRepository;
import com.videostep.locator.repository.AiCreationShareRepository;
import com.videostep.locator.repository.AiCreationVersionRepository;
import com.videostep.locator.repository.AiCreationVideoRepository;
import com.videostep.locator.repository.AiVideoTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 酿味会话 / 消息管理（CRUD + 属主校验 + 状态机流转单点）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationSessionService {

    private final AiCreationSessionRepository sessionRepository;
    private final AiCreationMessageRepository messageRepository;
    private final AiCreationRepository creationRepository;
    private final AiCreationVideoRepository videoRepository;
    private final AiVideoTaskRepository taskRepository;
    private final AiCreationVersionRepository versionRepository;
    private final AiCreationFeedbackRepository feedbackRepository;
    private final AiCreationShareRepository shareRepository;

    /** 创作文件根：{basePath}/creations/{creationId}/（video.mp4 + segments/ + tts/） */
    @Value("${video.storage.base-path}")
    private String basePath;

    /** 状态机允许流转表（非法流转抛 400） */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            AiCreationSession.STATUS_CREATED, Set.of(AiCreationSession.STATUS_GATHERING, AiCreationSession.STATUS_CONFIRMED),
            AiCreationSession.STATUS_GATHERING, Set.of(AiCreationSession.STATUS_GATHERING, AiCreationSession.STATUS_CONFIRMED),
            AiCreationSession.STATUS_CONFIRMED, Set.of(AiCreationSession.STATUS_GENERATING, AiCreationSession.STATUS_GATHERING),
            AiCreationSession.STATUS_GENERATING, Set.of(AiCreationSession.STATUS_RECIPE_READY, AiCreationSession.STATUS_GATHERING),
            AiCreationSession.STATUS_RECIPE_READY, Set.of(AiCreationSession.STATUS_RECIPE_READY, AiCreationSession.STATUS_CONFIRMED,
                    AiCreationSession.STATUS_GATHERING, AiCreationSession.STATUS_VIDEO_GEN, AiCreationSession.STATUS_DONE),
            AiCreationSession.STATUS_VIDEO_GEN, Set.of(AiCreationSession.STATUS_VIDEO_READY, AiCreationSession.STATUS_GATHERING),
            AiCreationSession.STATUS_VIDEO_READY, Set.of(AiCreationSession.STATUS_VIDEO_GEN, AiCreationSession.STATUS_DONE),
            AiCreationSession.STATUS_DONE, Set.of());

    public AiCreationSession createSession(Long userId, String title) {
        AiCreationSession s = AiCreationSession.builder()
                .userId(userId)
                .title(title == null || title.isBlank() ? "新创作" : title.trim())
                .status(AiCreationSession.STATUS_CREATED)
                .build();
        sessionRepository.insert(s);
        return s;
    }

    /** 属主校验：会话不存在或非本人所有时抛异常（→ 400） */
    public AiCreationSession getOwnedSession(Long userId, Long sessionId) {
        AiCreationSession s = sessionRepository.selectById(sessionId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        return s;
    }

    public List<Map<String, Object>> listSessions(Long userId) {
        List<AiCreationSession> sessions = sessionRepository.findByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiCreationSession s : sessions) {
            AiCreationMessage last = messageRepository.findLastBySessionId(s.getId());
            com.videostep.locator.entity.AiCreation c = creationRepository.findBySessionId(s.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("status", s.getStatus());
            m.put("lastPreview", last != null ? truncate(last.getContent(), 50) : "");
            m.put("messageCount", messageRepository.countBySessionId(s.getId()));
            m.put("creationId", c == null ? null : c.getId());
            m.put("updatedAt", s.getUpdatedAt());
            result.add(m);
        }
        return result;
    }

    public List<AiCreationMessage> getMessages(Long userId, Long sessionId) {
        getOwnedSession(userId, sessionId);
        return messageRepository.findBySessionId(sessionId);
    }

    public void addMessage(Long sessionId, String role, String content, String intentJson) {
        addMessage(sessionId, role, content, intentJson, null);
    }

    /** 支持附件 JSON 落库（P1） */
    public void addMessage(Long sessionId, String role, String content, String intentJson, String attachments) {
        AiCreationMessage m = AiCreationMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .intentJson(intentJson)
                .attachments(attachments)
                .build();
        messageRepository.insert(m);
        touch(sessionId);
    }

    /** 状态流转（单点校验，非法流转抛 400） */
    public void updateStatus(Long userId, Long sessionId, String to) {
        AiCreationSession s = getOwnedSession(userId, sessionId);
        transitionOrThrow(s.getStatus(), to);
        s.setStatus(to);
        sessionRepository.updateById(s);
    }

    /** 无校验直接落状态（内部流程使用，如创建会话后） */
    public void setStatusDirect(Long sessionId, String to) {
        AiCreationSession s = sessionRepository.selectById(sessionId);
        if (s != null) {
            s.setStatus(to);
            sessionRepository.updateById(s);
        }
    }

    public void updateIntent(Long sessionId, String intentJson) {
        AiCreationSession s = sessionRepository.selectById(sessionId);
        if (s != null) {
            s.setIntentJson(intentJson);
            sessionRepository.updateById(s);
        }
    }

    public String getIntent(Long userId, Long sessionId) {
        return getOwnedSession(userId, sessionId).getIntentJson();
    }

    public void renameSession(Long userId, Long sessionId, String title) {
        AiCreationSession s = getOwnedSession(userId, sessionId);
        s.setTitle(title == null || title.isBlank() ? "未命名创作" : title.trim());
        sessionRepository.updateById(s);
    }

    public void deleteSession(Long userId, Long sessionId) {
        AiCreationSession s = getOwnedSession(userId, sessionId);
        // 关联创作一并删除（含视频/TTS 文件与关联表），避免孤儿数据
        AiCreation c = creationRepository.findBySessionId(sessionId);
        Long creationId = c == null ? null : c.getId();
        if (c != null) {
            deleteCreationWithFiles(creationId);
        }
        // 消息无 DB 级联（无外键），需应用层显式删除
        messageRepository.delete(new LambdaQueryWrapper<AiCreationMessage>()
                .eq(AiCreationMessage::getSessionId, sessionId));
        sessionRepository.deleteById(s.getId());
        log.info("[酿味] 删除创作会话 userId={} sessionId={} creationId={}", userId, sessionId, creationId);
    }

    /**
     * 删除创作及其全部资源：磁盘文件（video.mp4/segments/tts）+ 关联表 + 创作行。
     * 供删除创作 / 删除会话（级联）复用。
     */
    public void deleteCreationWithFiles(Long creationId) {
        // 磁盘文件：{basePath}/creations/{creationId}/
        try {
            Path dir = Path.of(basePath, "creations", String.valueOf(creationId));
            if (Files.exists(dir)) {
                try (var s = Files.walk(dir)) {
                    s.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                            });
                }
                log.info("[酿味] 已删除创作文件目录 creationId={} dir={}", creationId, dir);
            }
        } catch (IOException e) {
            log.warn("[酿味] 删除创作文件失败 creationId={}: {}", creationId, e.getMessage());
        }
        videoRepository.delete(new LambdaQueryWrapper<AiCreationVideo>().eq(AiCreationVideo::getCreationId, creationId));
        taskRepository.delete(new LambdaQueryWrapper<AiVideoTask>().eq(AiVideoTask::getCreationId, creationId));
        versionRepository.delete(new LambdaQueryWrapper<AiCreationVersion>().eq(AiCreationVersion::getCreationId, creationId));
        feedbackRepository.delete(new LambdaQueryWrapper<AiCreationFeedback>().eq(AiCreationFeedback::getCreationId, creationId));
        shareRepository.delete(new LambdaQueryWrapper<AiCreationShare>().eq(AiCreationShare::getCreationId, creationId));
        creationRepository.deleteById(creationId);
        log.info("[酿味] 已删除创作数据 creationId={}", creationId);
    }

    public void touch(Long sessionId) {
        AiCreationSession touch = new AiCreationSession();
        touch.setId(sessionId);
        sessionRepository.updateById(touch);
    }

    private void transitionOrThrow(String from, String to) {
        Set<String> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new IllegalArgumentException("会话状态不允许从 " + from + " 流转到 " + to);
        }
    }

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
