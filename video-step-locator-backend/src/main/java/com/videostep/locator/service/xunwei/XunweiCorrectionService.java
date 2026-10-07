package com.videostep.locator.service.xunwei;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.UserCorrection;
import com.videostep.locator.entity.Video;
import com.videostep.locator.repository.UserCorrectionRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.VideoStepRepository;
import com.videostep.locator.service.GrowthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成品拍照纠偏（M1/M2）：实拍成品照 + 目标成品参照图（手动上传 / 视频成品帧 / 视频封面）→ 双图对比 → 结构化纠偏报告。
 * 参照图降级链（M2）：手动上传参照图 &gt; 视频成品帧（VideoFrameExtractor 抽取并缓存）&gt; 视频封面（ThumbnailProxy 抓取）。
 * 纠偏成功 → EXP+5（reason=correction）→ 落库 user_corrections（月度报告纠偏分析）→ 可选写入寻味会话（纯文本，sessionId）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class XunweiCorrectionService {

    private final XunweiVisionService xunweiVisionService;
    private final ThumbnailProxy thumbnailProxy;
    private final VideoRepository videoRepository;
    private final VideoStepRepository videoStepRepository;
    private final VideoFrameExtractor videoFrameExtractor;
    private final UserCorrectionRepository correctionRepository;
    private final CorrectionProfileService correctionProfileService;
    private final XunweiSessionService xunweiSessionService;
    private final GrowthService growthService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 纠偏成功 EXP（成长体系轻量激励，口径统一） */
    public static final int EXP_FOR_CORRECTION = 5;

    public Map<String, Object> correct(Long userId, MultipartFile file, MultipartFile referenceFile,
                                       Long videoId, String title, Long sessionId) {
        byte[] userBytes = bytesOf(file, "请先上传一张成品照片");
        Video video = null;
        if (videoId != null) video = videoRepository.selectById(videoId);
        String dishName = (title == null || title.isBlank()) ? (video == null ? null : video.getTitle()) : title;

        // 参照图降级链：手动上传 > 视频成品帧 > 视频封面
        byte[] ref;
        String refFilename;
        String source;
        if (referenceFile != null && !referenceFile.isEmpty()) {
            ref = bytesOf(referenceFile, "参照图为空");
            refFilename = referenceFile.getOriginalFilename();
            source = "manual";
        } else if (video != null) {
            byte[] frameBytes = null;
            Path frame = videoFrameExtractor.extractAndCache(video,
                    videoStepRepository.findByVideoIdOrderByStepIndexAsc(videoId));
            if (frame != null) {
                try {
                    frameBytes = Files.readAllBytes(frame);
                } catch (Exception e) {
                    log.warn("read finish frame failed videoId={}: {}", videoId, e.getMessage());
                }
            }
            if (frameBytes != null) {
                ref = frameBytes;
                refFilename = "frame.jpg";
                source = "frame";
            } else if (video.getThumbnail() != null && !video.getThumbnail().isBlank()) {
                ref = thumbnailProxy.fetch(video.getThumbnail());
                refFilename = "cover.jpg";
                source = "thumbnail";
            } else {
                throw new XunweiVisionException("暂无目标成品参照图，请上传一张参考图（或选择带封面的视频）");
            }
        } else {
            throw new XunweiVisionException("暂无目标成品参照图，请上传一张参考图（或选择带封面的视频）");
        }

        Map<String, Object> report = xunweiVisionService.correctDish(
                userBytes, file.getOriginalFilename(), ref, refFilename, dishName);

        // 成长信号：纠偏 → EXP+5（幂等、不抛异常、不影响主流程）
        growthService.addExp(userId, EXP_FOR_CORRECTION, "correction", videoId);

        // M2：落库 user_corrections（月度报告纠偏分析 / M3 易犯偏差画像）
        persistCorrection(userId, videoId, dishName, report, source);

        // M3：回填寻味记忆（个人易犯偏差画像，best-effort）
        correctionProfileService.syncWeaknessToMemory(userId);

        // M2：可选写入寻味会话（纯文本），便于用户回溯并继续追问
        if (sessionId != null) {
            xunweiSessionService.getOwnedSession(userId, sessionId);
            xunweiSessionService.addMessage(sessionId, "assistant", toSessionText(dishName, report), null);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", report.get("summary"));
        result.put("dimensions", report.get("dimensions"));
        result.put("reference", Map.of("source", source));
        result.put("expGained", EXP_FOR_CORRECTION);
        return result;
    }

    private void persistCorrection(Long userId, Long videoId, String dishName,
                                   Map<String, Object> report, String source) {
        try {
            List<?> dims = (List<?>) report.get("dimensions");
            correctionRepository.insert(UserCorrection.builder()
                    .userId(userId)
                    .videoId(videoId)
                    .recipeTitle(dishName)
                    .summary((String) report.get("summary"))
                    .dimensions(dims == null || dims.isEmpty() ? null : objectMapper.writeValueAsString(dims))
                    .referenceSource(source)
                    .expGained(EXP_FOR_CORRECTION)
                    .build());
        } catch (Exception e) {
            log.warn("persistCorrection failed userId={}: {}", userId, e.getMessage());
        }
    }

    /** 纠偏报告 → 会话纯文本消息（A 方案） */
    private String toSessionText(String dishName, Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("📷 成品纠偏");
        if (dishName != null && !dishName.isBlank()) sb.append(" · ").append(dishName);
        sb.append("\n").append(report.get("summary") == null ? "" : report.get("summary"));
        List<?> dims = (List<?>) report.get("dimensions");
        if (dims != null) {
            for (Object o : dims) {
                if (o instanceof Map<?, ?> d) {
                    String name = String.valueOf(d.get("name"));
                    String conclusion = String.valueOf(d.get("conclusion"));
                    String advice = String.valueOf(d.get("advice"));
                    if (name.isBlank()) continue;
                    sb.append("\n· ").append(name).append(" ").append(conclusion);
                    if (!advice.isBlank() && !"null".equals(advice)) sb.append("：").append(advice);
                }
            }
        }
        sb.append("\n（如需继续调整，可直接问我怎么改进）");
        return sb.toString();
    }

    private byte[] bytesOf(MultipartFile f, String errMsg) {
        if (f == null || f.isEmpty()) throw new XunweiVisionException(errMsg);
        try {
            return f.getBytes();
        } catch (Exception e) {
            throw new XunweiVisionException("图片读取失败：" + e.getMessage());
        }
    }
}

