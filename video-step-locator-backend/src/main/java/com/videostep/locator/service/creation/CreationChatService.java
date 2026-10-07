package com.videostep.locator.service.creation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiCreationMessage;
import com.videostep.locator.entity.AiCreationSession;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.creation.CreationRecipeGenerator.ReviseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酿味创作编排服务：多轮会话 → 意图收敛 → 菜谱生成 → 落库；不满意带反馈迭代（v0.3）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationChatService {

    /** 菜谱每 session 迭代次数上限（防死循环与成本失控，可配置 creation.iter-limit.recipe） */
    @Value("${creation.iter-limit.recipe:5}")
    private int maxIter;

    private final CreationSessionService sessionService;
    private final CreationIntentService intentService;
    private final CreationRecipeGenerator generator;
    private final CreationSafetyService safetyService;
    private final CreationRevisionService revisionService;
    private final CreationAttachmentResolver attachmentResolver;
    private final AiCreationRepository creationRepository;
    private final com.videostep.locator.repository.AiCreationFeedbackRepository feedbackRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------- 会话 ----------------

    public Map<String, Object> createSession(Long userId, String title) {
        AiCreationSession s = sessionService.createSession(userId, title);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", s.getId());
        r.put("title", s.getTitle());
        r.put("status", s.getStatus());
        return r;
    }

    // ---------------- 会话内发消息（核心） ----------------

    /** P0 文本版（兼容） */
    public CreationChatResult chatInSession(Long userId, Long sessionId, String message) {
        return chatInSession(userId, sessionId, message, null, null);
    }

    /** P1：支持多源附件（图片/系统视频引用/外部 URL），附件解析结果并入 LLM 上下文 */
    public CreationChatResult chatInSession(Long userId, Long sessionId, String message,
                                            String attachmentsJson, List<MultipartFile> files) {
        AiCreationSession session = sessionService.getOwnedSession(userId, sessionId);
        List<AiCreationMessage> history = sessionService.getMessages(userId, sessionId);
        String sessionIntent = session.getIntentJson();

        // 附件解析 → 并入上下文（不污染用户原始消息存储）
        String effectiveMessage = message;
        String resolved = resolveAttachments(attachmentsJson, files);
        if (!resolved.isBlank()) {
            effectiveMessage = (message == null ? "" : message) + "\n\n" + resolved;
        }
        log.info("[酿味] userId={} sessionId={} attachmentsJson={} resolved={}",
                userId, sessionId, attachmentsJson, resolved);

        CreationIntentService.IntentResult intent = intentService.understand(userId, history, effectiveMessage, sessionIntent);

        // 用户消息先落库（存原始文本 + 附件 JSON）
        sessionService.addMessage(sessionId, AiCreationMessage.ROLE_USER,
                message == null ? "" : message, null, attachmentsJson);

        switch (intent.action()) {
            case "ask" -> {
                if (AiCreationSession.STATUS_CREATED.equals(session.getStatus())) {
                    sessionService.setStatusDirect(sessionId, AiCreationSession.STATUS_GATHERING);
                }
                sessionService.updateIntent(sessionId, intent.intentJson());
                sessionService.addMessage(sessionId, AiCreationMessage.ROLE_ASSISTANT, intent.reply(), intent.intentJson());
                return new CreationChatResult("ask", intent.reply(), intent.intentJson(), null, null, null);
            }
            case "confirm" -> {
                // 幂等：已在 CONFIRMED 则跳过状态流转（否则 CONFIRMED→CONFIRMED 被状态机拒绝 → 400「会话状态有问题」）
                if (!AiCreationSession.STATUS_CONFIRMED.equals(session.getStatus())) {
                    sessionService.updateStatus(userId, sessionId, AiCreationSession.STATUS_CONFIRMED);
                }
                sessionService.updateIntent(sessionId, intent.intentJson());
                sessionService.addMessage(sessionId, AiCreationMessage.ROLE_ASSISTANT, intent.reply(), intent.intentJson());
                return new CreationChatResult("confirm", intent.reply(), intent.intentJson(), null, null, null);
            }
            case "revise_recipe" -> {
                AiCreation latest = findLatestCreation(sessionId);
                if (latest == null) {
                    sessionService.addMessage(sessionId, AiCreationMessage.ROLE_ASSISTANT,
                            "我还没为你生成过菜谱，请先确认创作意向。", intent.intentJson());
                    return new CreationChatResult("ask", "我还没为你生成过菜谱，请先确认创作意向。", intent.intentJson(), null, null, null);
                }
                ReviseResult out = doRevise(userId, latest, message);
                if ("ask".equals(out.action())) {
                    sessionService.addMessage(sessionId, AiCreationMessage.ROLE_ASSISTANT, out.reply(), intent.intentJson());
                    return new CreationChatResult("ask", out.reply(), intent.intentJson(), null, null, null);
                }
                sessionService.updateStatus(userId, sessionId, AiCreationSession.STATUS_RECIPE_READY);
                sessionService.addMessage(sessionId, AiCreationMessage.ROLE_ASSISTANT,
                        out.reply() + (out.deltaNote() == null || out.deltaNote().isBlank() ? "" : "（" + out.deltaNote() + "）"),
                        intent.intentJson());
                return new CreationChatResult("revise_recipe", out.reply(), intent.intentJson(),
                        latest.getId(), latest.getVersion(), out.safetyItems());
            }
            default -> {
                sessionService.addMessage(sessionId, AiCreationMessage.ROLE_ASSISTANT, intent.reply(), "{}");
                return new CreationChatResult("error", intent.reply(), "{}", null, null, null);
            }
        }
    }

    // ---------------- 确认 → 生成菜谱 ----------------

    public AiCreation confirm(Long userId, Long sessionId) {
        AiCreationSession session = sessionService.getOwnedSession(userId, sessionId);
        String status = session.getStatus();
        if (!AiCreationSession.STATUS_CONFIRMED.equals(status)) {
            // 会话不在 CONFIRMED（如安全校验后被拉回 GATHERING）→ 给出明确指引，避免状态机「GATHERING→GENERATING 不允许」的困惑
            throw new IllegalArgumentException("创作意向需要重新确认（可能未通过安全校验），请调整口味/忌口后重新发送你的想法");
        }
        sessionService.updateStatus(userId, sessionId, AiCreationSession.STATUS_GENERATING);

        String constraints = session.getIntentJson();
        AiCreation recipe = generator.generate(userId, constraints);

        // 安全红线校验
        Map<String, Object> safety = safetyService.check(userId, recipe);
        if (CreationSafetyService.LEVEL_RED.equals(safety.get("level"))) {
            sessionService.updateStatus(userId, sessionId, AiCreationSession.STATUS_GATHERING);
            String reason = String.valueOf(safety.get("reasons"));
            // 在聊天中追加说明，用户能看到「为什么没生成」
            sessionService.addMessage(sessionId, AiCreationMessage.ROLE_ASSISTANT,
                    "安全校验未通过：" + reason + "。为避免与您的忌口/偏好冲突，请调整口味或食材后重新告诉我，我再为您构思。",
                    session.getIntentJson());
            throw new IllegalArgumentException("创作未通过安全校验，已回到意图收集阶段：" + reason);
        }

        // 落库
        recipe.setUserId(userId);
        recipe.setSessionId(sessionId);
        recipe.setSourceType(AiCreation.SOURCE_TYPE_AI_CREATION);
        recipe.setVersion(1);
        recipe.setIterCount(0);
        recipe.setStatus(AiCreation.STATUS_PUBLISHED);
        creationRepository.insert(recipe);

        // 版本快照 v1
        revisionService.snapshot(recipe.getId(), 1, recipe.getSteps(), "初始创作");

        // 会话标题更新为菜名，状态 → RECIPE_READY
        sessionService.renameSession(userId, sessionId, recipe.getTitle());
        sessionService.updateStatus(userId, sessionId, AiCreationSession.STATUS_RECIPE_READY);
        log.info("[酿味] 菜谱生成完成 userId={} sessionId={} creationId={} title={}", userId, sessionId, recipe.getId(), recipe.getTitle());
        return recipe;
    }

    // ---------------- 菜谱不满意迭代（创建级） ----------------

    public Map<String, Object> revise(Long userId, Long creationId, String feedback) {
        AiCreation creation = getOwnedCreation(userId, creationId);
        int iter = creation.getIterCount() == null ? 0 : creation.getIterCount();
        if (iter >= maxIter) {
            throw new IllegalArgumentException("已达菜谱迭代上限（" + maxIter + " 次），可回退上一版或另起新创作");
        }
        ReviseResult out = doRevise(userId, creation, feedback);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", out.action());
        r.put("reply", out.reply());
        if ("revise".equals(out.action()) && out.recipe() != null) {
            r.put("creationId", creation.getId());
            r.put("version", creation.getVersion());
            r.put("deltaNote", out.deltaNote());
        }
        if (out.safetyItems() != null && !out.safetyItems().isEmpty()) {
            r.put("safetyJson", out.safetyItems());
        }
        return r;
    }

    /** 修订公共逻辑（会话内 revise_recipe 与创建级 /revise 共用） */
    private ReviseResult doRevise(Long userId, AiCreation current, String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return new ReviseResult("ask", "请告诉我希望怎么改（例如：更辣一点 / 主料换成豆腐 / 步骤精简一些）", null, null, null);
        }
        ReviseResult out = generator.revise(userId, current, feedback);
        if (!"revise".equals(out.action()) || out.recipe() == null) {
            return out; // ask：追问澄清
        }
        // 安全校验
        Map<String, Object> safety = safetyService.check(userId, out.recipe());
        if (CreationSafetyService.LEVEL_RED.equals(safety.get("level"))) {
            return new ReviseResult("ask", "修订未通过安全校验：" + safety.get("reasons") + "。请调整反馈后重试。", null, null, null);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> safetyItems = (List<Map<String, Object>>) safety.getOrDefault("safetyItems", List.of());
        int newVersion = (current.getVersion() == null ? 0 : current.getVersion()) + 1;
        // 快照当前现场
        revisionService.snapshot(current.getId(), newVersion, current.getSteps(), out.deltaNote());
        // 更新创作
        AiCreation u = out.recipe();
        current.setTitle(u.getTitle());
        current.setSummary(u.getSummary());
        current.setMainIngredients(u.getMainIngredients());
        current.setAuxiliaryIngredients(u.getAuxiliaryIngredients());
        current.setTasteTags(u.getTasteTags());
        current.setAllergens(u.getAllergens());
        current.setSpiceLevel(u.getSpiceLevel());
        current.setCalories(u.getCalories());
        current.setEstimatedTime(u.getEstimatedTime());
        current.setDifficulty(u.getDifficulty());
        current.setSteps(u.getSteps());
        current.setDeviceParams(u.getDeviceParams());
        current.setVersion(newVersion);
        current.setIterCount((current.getIterCount() == null ? 0 : current.getIterCount()) + 1);
        creationRepository.updateById(current);
        log.info("[酿味] 菜谱修订完成 creationId={} v{} iterCount={}", current.getId(), newVersion, current.getIterCount());
        return new ReviseResult("revise", out.reply(), current, out.deltaNote(), safetyItems);
    }

    // ---------------- 创作属主 / 查询 ----------------

    public AiCreation getOwnedCreation(Long userId, Long creationId) {
        AiCreation c = creationRepository.selectById(creationId);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new IllegalArgumentException("创作不存在或无权访问");
        }
        return c;
    }

    public AiCreation findLatestCreation(Long sessionId) {
        return creationRepository.selectList(new LambdaQueryWrapper<AiCreation>()
                        .eq(AiCreation::getSessionId, sessionId)
                        .orderByDesc(AiCreation::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    public Map<String, Object> getIntent(Long userId, Long sessionId) {
        AiCreationSession s = sessionService.getOwnedSession(userId, sessionId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", s.getId());
        r.put("status", s.getStatus());
        r.put("intentJson", s.getIntentJson());
        return r;
    }

    // ---------------- 我的创作（WP7） ----------------

    /** 解析附件 JSON → 注入 LLM 的上下文文本（失败不阻塞） */
    private String resolveAttachments(String attachmentsJson, List<MultipartFile> files) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) return "";
        String json = attachmentsJson;
        if (json.startsWith("\uFEFF")) json = json.substring(1); // 容错 UTF-8 BOM
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) return "";
            for (JsonNode a : arr) {
                String type = a.path("type").asText("");
                switch (type) {
                    case "image" -> {
                        int fi = a.path("fileIndex").asInt(-1);
                        MultipartFile f = (files != null && fi >= 0 && fi < files.size())
                                ? files.get(fi) : null;
                        if (f != null) {
                            try {
                                sb.append(attachmentResolver.resolveImage(
                                        f.getBytes(), f.getOriginalFilename())).append('\n');
                            } catch (Exception e) {
                                sb.append("[图片] 读取失败\n");
                            }
                        } else {
                            sb.append("[图片] 未收到文件\n");
                        }
                    }
                    case "video_ref" -> sb.append(attachmentResolver
                            .resolveVideoRef(a.path("videoId").asLong(0))).append('\n');
                    case "url" -> sb.append(attachmentResolver
                            .resolveUrl(a.path("url").asText(""))).append('\n');
                    case "audio" -> {
                        String voiceText = a.path("text").asText("").trim();
                        if (voiceText.isEmpty()) {
                            sb.append("[语音输入] 未识别到语音内容\n");
                        } else {
                            sb.append("[语音输入] ").append(voiceText).append('\n');
                        }
                    }
                    default -> { }
                }
            }
        } catch (Exception e) {
            log.warn("[酿味] 附件解析异常: {}", e.getMessage());
        }
        return sb.toString();
    }


    public Map<String, Object> listCreations(Long userId, int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        List<AiCreation> list = creationRepository.pageByUser(userId, offset, size);
        long total = creationRepository.countByUser(userId);
        List<Map<String, Object>> items = list.stream().map(CreationChatService::toListItem).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("total", total);
        r.put("page", page);
        r.put("size", size);
        return r;
    }

    public Map<String, Object> creationDetail(Long userId, Long creationId) {
        AiCreation c = getOwnedCreation(userId, creationId);
        Map<String, Object> m = toDetailMap(c);
        // P1：附 safetyJson（规则库校验结果，confirm/detail 自动覆盖）
        Map<String, Object> safety = safetyService.check(userId, c);
        Object items = safety.get("safetyItems");
        if (items != null) m.put("safetyJson", items);
        return m;
    }

    public void renameCreation(Long userId, Long creationId, String title) {
        AiCreation c = getOwnedCreation(userId, creationId);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        c.setTitle(title.trim());
        creationRepository.updateById(c);
    }

    /**
     * 编辑单步内容（摘要/详情/计时），仅改图文文本，不触发生成。
     * 已发布到常规菜谱库时同步镜像 steps_json，保持一致性。
     */
    public Map<String, Object> updateStep(Long userId, Long creationId, int stepIndex,
                                          String summary, String detail, Integer timerMin) {
        AiCreation c = getOwnedCreation(userId, creationId);
        List<Map<String, Object>> steps = CreationJsonUtil.parseSteps(c.getSteps());
        Map<String, Object> target = null;
        for (Map<String, Object> s : steps) {
            Object idx = s.get("index");
            int si = idx instanceof Number ? ((Number) idx).intValue() : 0;
            if (si == stepIndex) {
                target = s;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("步骤不存在");
        }
        if (summary != null && !summary.isBlank()) target.put("summary", summary.trim());
        if (detail != null) target.put("detail", detail.trim());
        if (timerMin != null) target.put("timer_min", timerMin);
        c.setSteps(CreationJsonUtil.toJson(steps));
        creationRepository.updateById(c);
        // 若已发布菜谱镜像，同步 steps（不影响已生成视频/TTS，无需重新生成）
        RecipeMetadata mirror = recipeMetadataRepository.findByCreationId(creationId);
        if (mirror != null) {
            mirror.setStepsJson(c.getSteps());
            recipeMetadataRepository.updateById(mirror);
        }
        log.info("[酿味] 编辑步骤 userId={} creationId={} stepIndex={} summary={}",
                userId, creationId, stepIndex, summary);
        return toDetailMap(c);
    }

    public void deleteCreation(Long userId, Long creationId) {
        AiCreation c = getOwnedCreation(userId, creationId);
        sessionService.deleteCreationWithFiles(c.getId());
        log.info("[酿味] 删除创作 userId={} creationId={}", userId, creationId);
    }

    /** 衍生创作：新建 session 并带入来源 constraints（creation_id 指向来源） */
    public Map<String, Object> deriveCreation(Long userId, Long creationId) {
        AiCreation c = getOwnedCreation(userId, creationId);
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("ingredients", CreationJsonUtil.parseStringArray(c.getMainIngredients()));
        constraints.put("tastes", CreationJsonUtil.parseStringArray(c.getTasteTags()));
        constraints.put("taboo", CreationJsonUtil.parseStringArray(c.getAllergens()));
        constraints.put("maxMinutes", c.getEstimatedTime() == null ? 0 : c.getEstimatedTime());
        constraints.put("scene", "");
        constraints.put("style", "");
        constraints.put("cuisine", "");
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("confirmed", false);
        intent.put("summary", "基于「" + c.getTitle() + "」再创作");
        intent.put("constraints", constraints);
        intent.put("missing", List.of());
        intent.put("reply", "已带入「" + c.getTitle() + "」作为灵感参考，请补充或调整想法。");

        AiCreationSession s = sessionService.createSession(userId, "新创作（基于 " + c.getTitle() + "）");
        s.setStatus(AiCreationSession.STATUS_GATHERING);
        s.setIntentJson(CreationJsonUtil.toJson(intent));
        // 状态从 CREATED→GATHERING（直接写，避免重复走 updateStatus 校验）
        sessionService.setStatusDirect(s.getId(), AiCreationSession.STATUS_GATHERING);
        sessionService.updateIntent(s.getId(), CreationJsonUtil.toJson(intent));
        // 记录衍生来源（预留字段）
        // （creation_id 在 confirm 落库时通过 controller 传入；此处会话已建好即可）

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", s.getId());
        r.put("status", s.getStatus());
        r.put("sourceCreationId", creationId);
        return r;
    }

    /** 口味反馈（写回记忆留 P1，先落库） */
    public void submitFeedback(Long userId, Long creationId, String rating, String comment) {
        getOwnedCreation(userId, creationId);
        if (!List.of("delicious", "ok", "failed").contains(rating)) {
            throw new IllegalArgumentException("评价类型非法");
        }
        com.videostep.locator.entity.AiCreationFeedback fb = com.videostep.locator.entity.AiCreationFeedback.builder()
                .creationId(creationId)
                .userId(userId)
                .rating(rating)
                .comment(comment)
                .build();
        feedbackRepository.insert(fb);
    }

    private static Map<String, Object> toListItem(AiCreation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("title", c.getTitle());
        m.put("summary", c.getSummary());
        m.put("tasteTags", CreationJsonUtil.parseStringArray(c.getTasteTags()));
        m.put("spiceLevel", c.getSpiceLevel());
        m.put("estimatedTime", c.getEstimatedTime());
        m.put("difficulty", c.getDifficulty());
        m.put("version", c.getVersion());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> toDetailMap(AiCreation c) {
        Map<String, Object> m = toListItem(c);
        m.put("summary", c.getSummary());
        m.put("mainIngredients", CreationJsonUtil.parseStringArray(c.getMainIngredients()));
        m.put("auxiliaryIngredients", CreationJsonUtil.parseStringArray(c.getAuxiliaryIngredients()));
        m.put("allergens", CreationJsonUtil.parseStringArray(c.getAllergens()));
        m.put("calories", c.getCalories());
        m.put("steps", CreationJsonUtil.parseSteps(c.getSteps()));
        m.put("deviceParams", CreationJsonUtil.parseStringArray(c.getDeviceParams()));
        m.put("sourceType", c.getSourceType());
        m.put("iterCount", c.getIterCount());
        m.put("disclaimer", "AI 创作菜谱仅供参考");
        // P0/P1：酱汁配方 + 效果图（sauceJson 为对象，effectImage 为 effect/ 相对路径）
        if (c.getSauceJson() != null && !c.getSauceJson().isBlank()) {
            com.fasterxml.jackson.databind.JsonNode n = CreationJsonUtil.read(c.getSauceJson());
            if (n.isObject()) {
                try {
                    m.put("sauceJson", objectMapper.convertValue(n, Map.class));
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
        m.put("effectImage", c.getEffectImage());
        m.put("effectImageStyle", c.getEffectImageStyle());
        return m;
    }
}
