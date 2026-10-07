package com.videostep.locator.service.creation;

import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiImageTask;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.repository.AiImageTaskRepository;
import com.videostep.locator.service.FileStorageService;
import com.videostep.locator.service.creation.imagegen.ImageGenOrchestrator;
import com.videostep.locator.service.creation.imagegen.ImageGenResult;
import com.videostep.locator.service.creation.imagegen.ImageGenTaskRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 酿味酱汁效果图编排（P1，仿 CreationVideoService 的「预估→确认→异步→轮询→下载→计费」范式）：
 * 整道菜一张；文生图网关路由（wan_image/mock，健康降级）；结果存 effect/ 目录 + ai_image_tasks +
 * ai_creations.effect_image + usage_records(actionType='imagegen'，按次)。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EffectImageService {

    private static final Set<String> STYLES = Set.of("realistic", "plated", "illustration");

    private final CreationChatService chatService;
    private final AiCreationRepository creationRepository;
    private final AiImageTaskRepository taskRepository;
    private final ImageGenOrchestrator imageGateway;
    private final CreationQuotaService quotaService;
    private final FileStorageService fileStorage;

    @Value("${creation.effect-image.cost-per-image:0.15}")
    private double costPerImage;

    @Value("${creation.effect-image.default-style:realistic}")
    private String defaultStyle;

    @Value("${creation.effect-image.retry:1}")
    private int retryLimit;

    /** 效果图异步执行器（长任务：提交/轮询/下载/落库） */
    private static final ExecutorService IMAGE_EXEC =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "creation-effect-image");
                t.setDaemon(true);
                return t;
            });
    /** 正在生成中的创作（防止重复触发同一创作并发生成） */
    private static final Set<Long> GENERATING = ConcurrentHashMap.newKeySet();

    /**
     * 触发效果图生成：付费引擎（WanImage）无 confirm 先返成本预估；确认后异步执行并立即返回 started。
     */
    public Map<String, Object> startEffectImage(Long userId, Long creationId, String style, boolean confirm) {
        AiCreation creation = chatService.getOwnedCreation(userId, creationId);
        String st = normalizeStyle(style);
        boolean billable = imageGateway.isBillable();
        // 付费引擎：先返回成本预估，用户确认后才执行（Mock 免费直接生成）
        if (billable && !confirm) {
            return estimate(creationId, st);
        }
        if (billable) {
            quotaService.assertCanGenerateImage(userId);
        }
        if (!GENERATING.add(creationId)) {
            throw new IllegalArgumentException("该创作正在生成效果图中，请稍候");
        }
        IMAGE_EXEC.submit(() -> {
            try {
                doGenerateImage(userId, creationId, st);
            } catch (Exception e) {
                log.error("[效果图] 异步生成异常 creationId={}: {}", creationId, e.getMessage(), e);
            } finally {
                GENERATING.remove(creationId);
            }
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("started", true);
        r.put("creationId", creationId);
        r.put("style", st);
        r.put("billable", billable);
        return r;
    }

    /** 成本预估（付费引擎确认前返回） */
    private Map<String, Object> estimate(Long creationId, String style) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("needConfirm", true);
        r.put("creationId", creationId);
        r.put("style", style);
        r.put("perImageCost", costPerImage);
        r.put("estimatedCost", round2(costPerImage));
        return r;
    }

    private void doGenerateImage(Long userId, Long creationId, String style) {
        AiCreation creation = chatService.getOwnedCreation(userId, creationId);
        String prompt = buildPrompt(creation, style);
        boolean billable = imageGateway.isBillable();

        // 重置旧任务与旧效果图
        for (AiImageTask old : taskRepository.findByCreationId(creationId)) {
            taskRepository.deleteById(old.getId());
        }
        fileStorage.deleteEffectImages(creationId);
        if (creation.getEffectImage() != null) {
            creation.setEffectImage(null);
            creation.setEffectImageStyle(null);
            creationRepository.updateById(creation);
        }

        AiImageTask task = AiImageTask.builder()
                .creationId(creationId).userId(userId)
                .provider(imageGateway.provider()).prompt(prompt)
                .style(style)
                .status(AiImageTask.STATUS_QUEUED).progress(0).genRound(1)
                .build();
        taskRepository.insert(task);

        try {
            task.setStatus(AiImageTask.STATUS_RUNNING);
            task.setProgress(10);
            taskRepository.updateById(task);

            ImageGenTaskRef ref = imageGateway.submit(prompt, style);
            task.setTaskRef(ref.taskRef());
            task.setProgress(30);
            taskRepository.updateById(task);

            ImageGenResult res = imageGateway.poll(ref.taskRef());
            // 下载到临时文件 → 交 FileStorageService 存 effect/ 目录（统一命名/防穿越）
            Path tmp = Files.createTempFile("effect_", ".png");
            try {
                imageGateway.download(res.resultUrl(), tmp.toString());
                byte[] bytes = Files.readAllBytes(tmp);
                String rel = fileStorage.saveEffectImage(creationId, bytes, style);
                task.setStatus(AiImageTask.STATUS_COMPLETED);
                task.setProgress(100);
                task.setImagePath(rel);
                task.setCompletedAt(LocalDateTime.now());
                taskRepository.updateById(task);

                creation.setEffectImage(rel);
                creation.setEffectImageStyle(style);
                creationRepository.updateById(creation);

                if (billable) {
                    task.setCost((int) Math.round(costPerImage * 100));
                    taskRepository.updateById(task);
                    quotaService.recordImage(userId, creationId);
                }
                log.info("[效果图] 生成完成 creationId={} style={} rel={}", creationId, style, rel);
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        } catch (Exception e) {
            log.error("[效果图] 生成失败 creationId={}: {}", creationId, e.getMessage());
            int retry = task.getRetryCount() == null ? 0 : task.getRetryCount();
            if (retry < retryLimit) {
                task.setRetryCount(retry + 1);
                taskRepository.updateById(task);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // 重试：重新执行（保留任务记录，插入新轮）
                doGenerateImage(userId, creationId, style);
                return;
            }
            task.setStatus(AiImageTask.STATUS_FAILED);
            task.setErrorMessage(e.getMessage());
            taskRepository.updateById(task);
        }
    }

    /** 效果图生成进度（任务列表 + 已确认效果图） */
    public Map<String, Object> imageStatus(Long userId, Long creationId) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (AiImageTask t : taskRepository.findByCreationId(creationId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getId());
            m.put("provider", t.getProvider());
            m.put("status", t.getStatus());
            m.put("progress", t.getProgress());
            m.put("errorMessage", t.getErrorMessage());
            tasks.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("creationId", creationId);
        r.put("effectImage", c.getEffectImage());
        r.put("effectImageStyle", c.getEffectImageStyle());
        r.put("tasks", tasks);
        return r;
    }

    /** 返回效果图资源（鉴权由 controller 处理；文件缺失返回 null） */
    public Resource resolveEffectImage(Long userId, Long creationId) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        if (c.getEffectImage() == null || c.getEffectImage().isBlank()) return null;
        return fileStorage.loadEffectImage(c.getEffectImage());
    }

    private String buildPrompt(AiCreation c, String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("为菜品「").append(safe(c.getTitle())).append("」生成一张精美效果图。");
        if (c.getSummary() != null && !c.getSummary().isBlank()) {
            sb.append("创作理念：").append(c.getSummary()).append("。");
        }
        sb.append("主要食材：").append(String.join("、", CreationJsonUtil.parseStringArray(c.getMainIngredients()))).append("。");
        sb.append("辅料/调料：").append(String.join("、", CreationJsonUtil.parseStringArray(c.getAuxiliaryIngredients()))).append("。");
        String styleDesc = switch (style) {
            case "plated" -> "精致摆盘、餐厅出品、俯拍视角、高质感餐具";
            case "illustration" -> "明亮美食插画风格、扁平化、色彩鲜艳、可爱讨喜";
            default -> "写实美食摄影、自然光、专业摆盘、垂涎欲滴";
        };
        sb.append("画面风格：").append(styleDesc).append("。");
        sb.append("画面干净，无文字、无水印。");
        return sb.toString();
    }

    private String normalizeStyle(String style) {
        if (style == null || style.isBlank()) return defaultStyle;
        String s = style.trim().toLowerCase(Locale.ROOT);
        return STYLES.contains(s) ? s : defaultStyle;
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
