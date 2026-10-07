package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiCreationMessage;
import com.videostep.locator.entity.AiCreationVersion;
import com.videostep.locator.service.creation.CreationChatService;
import com.videostep.locator.service.creation.CreationChatResult;
import com.videostep.locator.service.creation.CreationExportService;
import com.videostep.locator.service.creation.CreationJsonUtil;
import com.videostep.locator.service.creation.CreationRevisionService;
import com.videostep.locator.service.creation.CreationSessionService;
import com.videostep.locator.service.creation.CreationPublishService;
import com.videostep.locator.service.creation.CreationQuotaService;
import com.videostep.locator.service.creation.CreationShareService;
import com.videostep.locator.service.creation.CreationTtsService;
import com.videostep.locator.service.creation.CreationTutorService;
import com.videostep.locator.service.creation.CreationVideoService;
import com.videostep.locator.service.creation.EffectImageService;
import com.videostep.locator.service.creation.SauceDesignService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「酿味」AI 菜品创作接口（P0：多轮共创 → 意图确认 → 菜谱生成 → 迭代 → 图文跟做 + 版本回退）。
 * 默认走 /api/** JWT 认证（仅登录用户可用）。
 */
@RestController
@RequestMapping("/api/creation")
@RequiredArgsConstructor
public class CreationController {

    private final CreationChatService chatService;
    private final CreationSessionService sessionService;
    private final CreationRevisionService revisionService;
    private final CreationTutorService tutorService;
    private final CreationVideoService videoService;
    private final CreationPublishService publishService;
    private final CreationExportService exportService;
    private final CreationQuotaService quotaService;
    private final CreationShareService shareService;
    private final CreationTtsService ttsService;
    private final SauceDesignService sauceService;
    private final EffectImageService effectImageService;

    // ---------------- 会话与多轮共创 ----------------

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Object>> createSession(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestBody(required = false) Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        return ApiResponse.success(chatService.createSession(requireUserId(userId), title));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ApiResponse.success(sessionService.listSessions(requireUserId(userId)));
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<AiCreationMessage>> getMessages(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(sessionService.getMessages(requireUserId(userId), id));
    }

    /** 会话内发消息（JSON 文本版，兼容 P0） */
    @PostMapping(value = "/sessions/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> sendMessage(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ApiResponse.error(400, "消息不能为空");
        }
        CreationChatResult r = chatService.chatInSession(requireUserId(userId), id, message);
        return toMessageResponse(id, r);
    }

    /** 会话内发消息（multipart：text + attachments JSON + files，支持多源附件，P1） */
    @PostMapping(value = "/sessions/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> sendMessageWithAttachments(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "attachments", required = false) String attachments,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        String message = text == null ? "" : text;
        boolean hasAttachment = attachments != null && !attachments.isBlank();
        if (message.isBlank() && !hasAttachment) {
            return ApiResponse.error(400, "消息或附件不能为空");
        }
        CreationChatResult r = chatService.chatInSession(requireUserId(userId), id, message, attachments, files);
        return toMessageResponse(id, r);
    }

    private ApiResponse<Map<String, Object>> toMessageResponse(Long id, CreationChatResult r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", id);
        data.put("action", r.action());
        data.put("reply", r.reply());
        data.put("intentJson", r.intentJson());
        if (r.creationId() != null) data.put("creationId", r.creationId());
        if (r.version() != null) data.put("version", r.version());
        // P1：规则库命中附 safetyJson
        if (r.safetyItems() != null && !r.safetyItems().isEmpty()) data.put("safetyJson", r.safetyItems());
        return ApiResponse.success(data);
    }

    @GetMapping("/sessions/{id}/intent")
    public ApiResponse<Map<String, Object>> getIntent(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(chatService.getIntent(requireUserId(userId), id));
    }

    /** 确认创作意图 → 触发菜谱生成 */
    @PostMapping("/sessions/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        AiCreation c = chatService.confirm(requireUserId(userId), id);
        Map<String, Object> data = chatService.creationDetail(requireUserId(userId), c.getId());
        return ApiResponse.success(data);
    }

    @PutMapping("/sessions/{id}")
    public ApiResponse<Void> renameSession(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return ApiResponse.error(400, "标题不能为空");
        }
        sessionService.renameSession(requireUserId(userId), id, title);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        sessionService.deleteSession(requireUserId(userId), id);
        return ApiResponse.success(null);
    }

    // ---------------- 我的创作 ----------------

    @GetMapping("/creations")
    public ApiResponse<Map<String, Object>> listCreations(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(chatService.listCreations(requireUserId(userId), page, size));
    }

    @GetMapping("/creations/{id}")
    public ApiResponse<Map<String, Object>> creationDetail(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(chatService.creationDetail(requireUserId(userId), id));
    }

    @PutMapping("/creations/{id}")
    public ApiResponse<Void> renameCreation(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        chatService.renameCreation(requireUserId(userId), id, body.get("title"));
        return ApiResponse.success(null);
    }

    /** 编辑单步内容（摘要/详情/计时，仅改图文文本不触发生成） */
    @PutMapping("/creations/{id}/steps/{stepIndex}")
    public ApiResponse<Map<String, Object>> updateStep(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @PathVariable Integer stepIndex,
            @RequestBody Map<String, Object> body) {
        String summary = (String) body.get("summary");
        String detail = (String) body.get("detail");
        Integer timerMin = body.get("timerMin") == null ? null
                : ((Number) body.get("timerMin")).intValue();
        return ApiResponse.success(chatService.updateStep(
                requireUserId(userId), id, stepIndex, summary, detail, timerMin));
    }

    @DeleteMapping("/creations/{id}")
    public ApiResponse<Void> deleteCreation(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        chatService.deleteCreation(requireUserId(userId), id);
        return ApiResponse.success(null);
    }

    /** 以此创作为基础衍生新创作 */
    @PostMapping("/creations/{id}/derive")
    public ApiResponse<Map<String, Object>> derive(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(chatService.deriveCreation(requireUserId(userId), id));
    }

    /** 菜谱不满意迭代（携带反馈 → 重新意图理解与生成，v0.3） */
    @PostMapping("/creations/{id}/revise")
    public ApiResponse<Map<String, Object>> revise(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String feedback = body.get("feedback");
        return ApiResponse.success(chatService.revise(requireUserId(userId), id, feedback));
    }

    @GetMapping("/creations/{id}/versions")
    public ApiResponse<List<Map<String, Object>>> listVersions(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        chatService.getOwnedCreation(requireUserId(userId), id);
        List<AiCreationVersion> versions = revisionService.listVersions(id);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiCreationVersion v : versions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", v.getVersion());
            m.put("steps", CreationJsonUtil.parseSteps(v.getStepsJson()));
            m.put("deltaNote", v.getDeltaNote());
            m.put("createdAt", v.getCreatedAt());
            items.add(m);
        }
        return ApiResponse.success(items);
    }

    @PostMapping("/creations/{id}/revert/{version}")
    public ApiResponse<Map<String, Object>> revert(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @PathVariable Integer version) {
        AiCreation c = chatService.getOwnedCreation(requireUserId(userId), id);
        AiCreation reverted = revisionService.revert(requireUserId(userId), c, version);
        return ApiResponse.success(chatService.creationDetail(requireUserId(userId), reverted.getId()));
    }

    /** 图文跟做 + 计时器（无视频兜底） */
    @PostMapping("/creations/{id}/cook")
    public ApiResponse<Map<String, Object>> cook(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(tutorService.cook(requireUserId(userId), id));
    }

    /** 口味反馈（delicious/ok/failed） */
    @PostMapping("/creations/{id}/feedback")
    public ApiResponse<Void> feedback(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        chatService.submitFeedback(requireUserId(userId), id, body.get("rating"), body.get("comment"));
        return ApiResponse.success(null);
    }

    // ---------------- AI 视频教程（P2 Mock 链路） ----------------

    /** 触发视频教程生成（逐段生成 → FFmpeg 拼接 → 步骤时间戳融合）；付费引擎需 confirm=true；异步长任务，进度经 /video-gen/tasks 轮询 */
    @PostMapping("/creations/{id}/video-gen")
    public ApiResponse<Map<String, Object>> videoGen(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm) {
        return ApiResponse.success(videoService.startVideoGeneration(requireUserId(userId), id, confirm));
    }

    /** 视频单段/整条重创（v0.3）：body {scope: step|all, stepIndex?} → 异步重创，进度经 /video-gen/tasks 轮询 */
    @PostMapping("/creations/{id}/video-regenerate")
    public ApiResponse<Map<String, Object>> videoRegenerate(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        String scope = body == null || body.get("scope") == null ? "step" : String.valueOf(body.get("scope"));
        Integer stepIndex = null;
        if (body != null && body.get("stepIndex") != null) {
            stepIndex = ((Number) body.get("stepIndex")).intValue();
        }
        return ApiResponse.success(videoService.startVideoRegenerate(requireUserId(userId), id, scope, stepIndex));
    }

    /** 今日视频生成配额用量 */
    @GetMapping("/video-gen/usage")
    public ApiResponse<Map<String, Object>> videoUsage(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ApiResponse.success(quotaService.todayUsage(requireUserId(userId)));
    }

    /** 视频生成任务进度 */
    @GetMapping("/video-gen/tasks")
    public ApiResponse<Map<String, Object>> videoTasks(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam Long creationId) {
        return ApiResponse.success(videoService.videoStatus(requireUserId(userId), creationId));
    }

    // ---------------- 酱汁设计（P0，纯 LLM） ----------------

    /** 设计酱汁（body: {demand?} 可选需求）→ 返回结构化配方（未落库，确认后落库） */
    @PostMapping("/creations/{id}/sauce")
    public ApiResponse<Map<String, Object>> designSauce(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String demand = body == null ? null : body.get("demand");
        return ApiResponse.success(sauceService.design(requireUserId(userId), id, demand));
    }

    /** 酱汁迭代（body: {demand} 更辣/低脂/无糖等）→ {action, reply, sauce?, deltaNote?} */
    @PostMapping("/creations/{id}/sauce/revise")
    public ApiResponse<Map<String, Object>> reviseSauce(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String demand = body == null ? null : body.get("demand");
        return ApiResponse.success(sauceService.revise(requireUserId(userId), id, demand));
    }

    /** 确认酱汁（body: {sauce: {...}} 当前配方）→ 安全校验通过后落库 sauce_json */
    @PostMapping("/creations/{id}/sauce/confirm")
    public ApiResponse<Map<String, Object>> confirmSauce(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sauce = body == null ? null
                : (Map<String, Object>) body.get("sauce");
        return ApiResponse.success(sauceService.confirm(requireUserId(userId), id, sauce));
    }

    // ---------------- 酱汁效果图（P1，文生图 + 成本控制） ----------------

    /** 触发效果图生成：付费引擎无 confirm 先返成本预估；确认后异步生成（进度经 /effect-image/tasks 轮询） */
    @PostMapping("/creations/{id}/effect-image")
    public ApiResponse<Map<String, Object>> effectImage(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm,
            @RequestParam(value = "style", required = false) String style) {
        return ApiResponse.success(
                effectImageService.startEffectImage(requireUserId(userId), id, style, confirm));
    }

    /** 效果图生成任务进度（任务列表 + 已确认效果图） */
    @GetMapping("/effect-image/tasks")
    public ApiResponse<Map<String, Object>> effectImageTasks(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam Long creationId) {
        return ApiResponse.success(
                effectImageService.imageStatus(requireUserId(userId), creationId));
    }

    /** 今日效果图配额用量 */
    @GetMapping("/effect-image/usage")
    public ApiResponse<Map<String, Object>> effectImageUsage(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ApiResponse.success(quotaService.todayImageUsage(requireUserId(userId)));
    }

    /** 播放/预览效果图（鉴权流，effect/ 目录） */
    @GetMapping("/creations/{id}/effect-image")
    public ResponseEntity<Resource> effectImageFile(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        Resource res = effectImageService.resolveEffectImage(requireUserId(userId), id);
        if (res == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/png")
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(res);
    }

    /** 播放 AI 生成视频（鉴权流 + Range 支持） */
    @GetMapping("/creations/{id}/video")
    public ResponseEntity<Resource> video(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        Path path;
        try {
            path = videoService.resolveVideoFile(requireUserId(userId), id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        try {
            long fileLength = Files.size(path);
            String contentType = Files.probeContentType(path);
            if (contentType == null) contentType = "video/mp4";
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileLength))
                        .header("Accept-Ranges", "bytes")
                        .body(new FileSystemResource(path));
            }
            String range = rangeHeader.substring(6).split(",")[0].trim();
            String[] parts = range.split("-");
            long start = Long.parseLong(parts[0]);
            long end = parts.length > 1 && !parts[1].isEmpty()
                    ? Long.parseLong(parts[1]) : fileLength - 1;
            if (start >= fileLength) start = fileLength - 1;
            if (end >= fileLength) end = fileLength - 1;
            if (start < 0) start = 0;
            if (end < start) end = start;
            long contentLength = end - start + 1;
            InputStream fullStream = Files.newInputStream(path);
            fullStream.skip(start);
            InputStreamResource rangeResource = new InputStreamResource(
                    new LimitedInputStream(fullStream, contentLength));
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + fileLength)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                    .header("Accept-Ranges", "bytes")
                    .body(rangeResource);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ---------------- TTS 语音讲解（阿里云 qwen3-tts-instruct-flash） ----------------

    /** 生成/刷新语音讲解（对视频步骤逐段合成，幂等） */
    @PostMapping("/creations/{id}/tts/generate")
    public ApiResponse<Map<String, Object>> ttsGenerate(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(ttsService.generateNarrations(requireUserId(userId), id));
    }

    /** 语音讲解状态（哪些步骤已就绪 + 文本 + 时长） */
    @GetMapping("/creations/{id}/tts")
    public ApiResponse<Map<String, Object>> ttsStatus(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(ttsService.narrationsStatus(requireUserId(userId), id));
    }

    /** 播放某步骤讲解音频（鉴权流 + Range 支持） */
    @GetMapping("/creations/{id}/tts/{stepIndex}")
    public ResponseEntity<Resource> ttsAudio(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @PathVariable Integer stepIndex,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        Path path;
        try {
            path = ttsService.resolveNarrationFile(requireUserId(userId), id, stepIndex);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        try {
            long fileLength = Files.size(path);
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, "audio/wav")
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileLength))
                        .header("Accept-Ranges", "bytes")
                        .body(new FileSystemResource(path));
            }
            String range = rangeHeader.substring(6).split(",")[0].trim();
            String[] parts = range.split("-");
            long start = Long.parseLong(parts[0]);
            long end = parts.length > 1 && !parts[1].isEmpty()
                    ? Long.parseLong(parts[1]) : fileLength - 1;
            if (start >= fileLength) start = fileLength - 1;
            if (end >= fileLength) end = fileLength - 1;
            if (start < 0) start = 0;
            if (end < start) end = start;
            long contentLength = end - start + 1;
            InputStream fullStream = Files.newInputStream(path);
            fullStream.skip(start);
            InputStreamResource rangeResource = new InputStreamResource(
                    new LimitedInputStream(fullStream, contentLength));
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_TYPE, "audio/wav")
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + fileLength)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                    .header("Accept-Ranges", "bytes")
                    .body(rangeResource);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 播放某步骤的视频分段（鉴权流 + Range 支持，供单段预览） */
    @GetMapping("/creations/{id}/segment/{stepIndex}")
    public ResponseEntity<Resource> segmentVideo(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @PathVariable Integer stepIndex,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        Path path;
        try {
            path = videoService.resolveSegmentFile(requireUserId(userId), id, stepIndex);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        try {
            long fileLength = Files.size(path);
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileLength))
                        .header("Accept-Ranges", "bytes")
                        .body(new FileSystemResource(path));
            }
            String range = rangeHeader.substring(6).split(",")[0].trim();
            String[] parts = range.split("-");
            long start = Long.parseLong(parts[0]);
            long end = parts.length > 1 && !parts[1].isEmpty()
                    ? Long.parseLong(parts[1]) : fileLength - 1;
            if (start >= fileLength) start = fileLength - 1;
            if (end >= fileLength) end = fileLength - 1;
            if (start < 0) start = 0;
            if (end < start) end = start;
            long contentLength = end - start + 1;
            InputStream fullStream = Files.newInputStream(path);
            fullStream.skip(start);
            InputStreamResource rangeResource = new InputStreamResource(
                    new LimitedInputStream(fullStream, contentLength));
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + fileLength)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                    .header("Accept-Ranges", "bytes")
                    .body(rangeResource);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ---------------- 升 B：公开投稿（镜像入统一菜谱库） ----------------

    @PostMapping("/creations/{id}/publish")
    public ApiResponse<Map<String, Object>> publish(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(publishService.syncToLibrary(requireUserId(userId), id));
    }

    // ---------------- 升 B：AI视频+同步语音 → 普通视频（方案B合成，v0.13） ----------------

    /** 导出 AI 视频为普通视频（讲解语音按步骤时间戳合成进音轨），建 videos 记录入审核队列 */
    @PostMapping("/creations/{id}/export-video")
    public ApiResponse<Map<String, Object>> exportVideo(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(exportService.exportVideo(requireUserId(userId), id));
    }

    // ---------------- 站外分享（P4） ----------------

    /** 生成创作分享链接（owner） */
    @PostMapping("/creations/{id}/share")
    public ApiResponse<Map<String, Object>> share(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Integer days = body == null || body.get("expireDays") == null
                ? null : ((Number) body.get("expireDays")).intValue();
        return ApiResponse.success(shareService.generate(requireUserId(userId), id, days));
    }

    /** 匿名只读解析分享（站外，permitAll） */
    @GetMapping("/share/{code}")
    public ApiResponse<Map<String, Object>> resolveShare(@PathVariable String code) {
        return ApiResponse.success(shareService.resolve(code));
    }

    /** 限制 InputStream 只读取指定字节数 */
    private static class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count = 0;

        protected LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (count >= limit) return -1;
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (count >= limit) return -1;
            int max = (int) Math.min(len, limit - count);
            int n = super.read(b, off, max);
            if (n > 0) count += n;
            return n;
        }
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }
        return userId;
    }
}
