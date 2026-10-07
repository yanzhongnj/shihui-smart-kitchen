package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.XunweiMessage;
import com.videostep.locator.service.xunwei.ChatResult;
import com.videostep.locator.service.xunwei.XunweiChatService;
import com.videostep.locator.service.xunwei.XunweiCorrectionService;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import com.videostep.locator.service.xunwei.XunweiSessionService;
import com.videostep.locator.service.xunwei.XunweiVisionException;
import com.videostep.locator.service.xunwei.XunweiVisionService;
import com.videostep.locator.service.xunwei.CorrectionProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 「寻味」AI 对话式做菜视频搜索接口。
 *
 * 默认走 /api/** 认证（仅登录用户可用，游客返回 401）。
 * M1：无状态 /chat；M2：会话 CRUD + 会话内发消息；M3：搜索+推荐；M4：长期记忆管理。
 */
@RestController
@RequestMapping("/api/xunwei")
@RequiredArgsConstructor
public class XunweiController {

    private final XunweiChatService xunweiChatService;
    private final XunweiSessionService xunweiSessionService;
    private final XunweiMemoryService xunweiMemoryService;
    private final XunweiVisionService xunweiVisionService;
    private final XunweiCorrectionService xunweiCorrectionService;
    private final CorrectionProfileService correctionProfileService;

    /** M1 无状态聊天（保留兼容，APP 主用会话接口） */
    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ApiResponse.error(400, "消息不能为空");
        }
        ChatResult result = xunweiChatService.chat(userId == null ? 0L : userId, message);
        return ApiResponse.success(Map.of(
                "action", result.action(),
                "reply", result.reply(),
                "queries", result.queries(),
                "videos", result.videos()));
    }

    // ---------------- 会话管理 ----------------

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Object>> createSession(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestBody(required = false) Map<String, String> body) {
        Long uid = requireUserId(userId);
        String title = body == null ? null : body.get("title");
        // 批量合并（3.2）：新建会话 = 上一段对话结束 → 冲刷上一会话攒下的对话缓冲，一次性批量抽取画像
        xunweiMemoryService.flushExtraction(uid);
        var s = xunweiSessionService.createSession(uid, title);
        return ApiResponse.success(Map.of("sessionId", s.getId(), "title", s.getTitle()));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ApiResponse.success(xunweiSessionService.listSessions(requireUserId(userId)));
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<XunweiMessage>> getMessages(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(xunweiSessionService.getMessages(requireUserId(userId), id));
    }

    /** 会话内发消息（核心） */
    @PostMapping("/sessions/{id}/messages")
    public ApiResponse<Map<String, Object>> sendMessage(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ApiResponse.error(400, "消息不能为空");
        }
        ChatResult r = xunweiChatService.chatInSession(requireUserId(userId), id, message);
        return ApiResponse.success(Map.of(
                "sessionId", id,
                "action", r.action(),
                "reply", r.reply(),
                "queries", r.queries(),
                "videos", r.videos()));
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
        xunweiSessionService.renameSession(requireUserId(userId), id, title);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        Long uid = requireUserId(userId);
        // 批量合并（3.2）：删除会话前冲刷该会话攒下的对话缓冲（对话内容仍有学习价值）
        xunweiMemoryService.flushExtraction(uid);
        xunweiSessionService.deleteSession(uid, id);
        return ApiResponse.success(null);
    }

    // ---------------- 长期记忆（用户画像） ----------------

    @GetMapping("/memory")
    public ApiResponse<List<Object>> listMemory(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ApiResponse.success(
                new java.util.ArrayList<>(xunweiMemoryService.list(requireUserId(userId))));
    }

    @DeleteMapping("/memory")
    public ApiResponse<Void> clearMemory(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        xunweiMemoryService.clear(requireUserId(userId));
        return ApiResponse.success(null);
    }

    /** 删除单条记忆（属主校验） */
    @DeleteMapping("/memory/{id}")
    public ApiResponse<Void> deleteMemory(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        if (!xunweiMemoryService.deleteOne(requireUserId(userId), id)) {
            return ApiResponse.error(404, "记忆不存在或无权操作");
        }
        return ApiResponse.success(null);
    }

    /** 修改单条记忆内容（属主校验，用于用户纠正画像） */
    @PutMapping("/memory/{id}")
    public ApiResponse<Void> updateMemory(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String value = body.get("memValue");
        if (value == null || value.isBlank()) {
            return ApiResponse.error(400, "记忆内容不能为空");
        }
        if (!xunweiMemoryService.updateOne(requireUserId(userId), id, value)) {
            return ApiResponse.error(404, "记忆不存在或无权操作");
        }
        return ApiResponse.success(null);
    }

    /** 偏好自进化设置：查询开关 */
    @GetMapping("/memory/settings")
    public ApiResponse<Map<String, Object>> getMemorySettings(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        Long uid = requireUserId(userId);
        return ApiResponse.success(Map.of("autoEvolve", xunweiMemoryService.isAutoEvolveEnabled(uid)));
    }

    /** 偏好自进化设置：开启/暂停 */
    @PutMapping("/memory/settings")
    public ApiResponse<Map<String, Object>> updateMemorySettings(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestBody Map<String, Object> body) {
        Long uid = requireUserId(userId);
        Boolean enabled = body.get("autoEvolve") instanceof Boolean b ? b : null;
        if (enabled == null) {
            return ApiResponse.error(400, "autoEvolve 不能为空");
        }
        xunweiMemoryService.setAutoEvolve(uid, enabled);
        return ApiResponse.success(Map.of("autoEvolve", enabled));
    }

    // ---------------- 拍照识食材 + 闭环推荐 ----------------

    /** 拍照识食材：上传图片 → 视觉模型识别 → 返回食材列表（不触发搜索） */
    @PostMapping("/image/recognize")
    public ApiResponse<Map<String, Object>> recognizeIngredients(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam("file") MultipartFile file) {
        requireUserId(userId);
        try {
            List<String> ingredients =
                    xunweiVisionService.recognizeIngredients(file.getBytes(), file.getOriginalFilename());
            return ApiResponse.success(Map.of(
                    "ingredients", ingredients,
                    "summary", ingredients.isEmpty() ? "未识别到食材"
                            : "识别到 " + ingredients.size() + " 种食材：" + String.join("、", ingredients)));
        } catch (Exception e) {
            return ApiResponse.error(400, visionError(e));
        }
    }

    /**
     * 拍照识食材闭环：识别食材 → 结合用户画像（口味/忌口/菜系）→ 寻味推荐/搜索做菜视频。
     * 支持传 sessionId 走会话（保留上下文、落库），否则走无状态 chat。
     */
    @PostMapping("/image/recommend")
    public ApiResponse<Map<String, Object>> recommendByImage(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam("file") MultipartFile file) {
        Long uid = requireUserId(userId);
        try {
            List<String> ingredients =
                    xunweiVisionService.recognizeIngredients(file.getBytes(), file.getOriginalFilename());
            if (ingredients.isEmpty()) {
                return ApiResponse.error(400, "未从图片中识别到食材，请换一张更清晰的食材照片重试");
            }
            String message = "我拍了张食材照片，识别出这些食材：" + String.join("、", ingredients)
                    + "。请结合我的口味和忌口偏好，推荐能用这些食材做的菜，并搜索相关做菜视频。";
            ChatResult r;
            if (sessionId != null) {
                r = xunweiChatService.chatInSession(uid, sessionId, message);
            } else {
                r = xunweiChatService.chat(uid, message);
            }
            return ApiResponse.success(Map.of(
                    "ingredients", ingredients,
                    "action", r.action(),
                    "reply", r.reply(),
                    "queries", r.queries(),
                    "videos", r.videos()));
        } catch (Exception e) {
            return ApiResponse.error(400, visionError(e));
        }
    }

    /**
     * 成品拍照纠偏：上传实拍成品照 + 参照图（关联视频封面 / 手动上传 referenceFile）→ 双图对比 → 结构化纠偏报告。
     * 参照图优先级：referenceFile > videoId 对应视频封面。纠偏成功 → EXP+5。
     */
    @PostMapping("/image/correct")
    public ApiResponse<Map<String, Object>> correctByImage(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "referenceFile", required = false) MultipartFile referenceFile,
            @RequestParam(value = "videoId", required = false) Long videoId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        Long uid = requireUserId(userId);
        try {
            return ApiResponse.success(
                    xunweiCorrectionService.correct(uid, file, referenceFile, videoId, title, sessionId));
        } catch (Exception e) {
            return ApiResponse.error(400, visionError(e));
        }
    }

    /** 个人易犯偏差画像（M3）：聚合历史纠偏 → 「你最常火候偏深」 */
    @GetMapping("/correction/profile")
    public ApiResponse<Map<String, Object>> correctionProfile(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ApiResponse.success(correctionProfileService.profile(requireUserId(userId)));
    }

    /** 视觉错误 → 用户可读提示 */
    private String visionError(Exception e) {
        if (e instanceof XunweiVisionException ve) {
            return ve.getMessage();
        }
        return "寻味视觉处理失败：" + e.getMessage();
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }
        return userId;
    }
}
