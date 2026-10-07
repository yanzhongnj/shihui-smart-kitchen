package com.videostep.locator.service.pipeline;

import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoStep;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.VideoStepRepository;
import com.videostep.locator.service.pipeline.PythonPipelineRunner.PipelineResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StepResultParser {

    private final VideoRepository videoRepository;
    private final VideoStepRepository stepRepository;

    public void saveSteps(Long videoId, PipelineResult result) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new RuntimeException("Video not found: " + videoId);

        // 更新视频时长
        if (result.getDuration() != null) {
            video.setDuration(BigDecimal.valueOf(result.getDuration().doubleValue()));
        }

        // 删除旧的步骤数据（重新处理时）
        stepRepository.deleteByVideoId(videoId);

        // 保存新步骤
        List<Map<String, Object>> steps = result.getSteps();
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                Map<String, Object> s = steps.get(i);
                VideoStep step = VideoStep.builder()
                        .videoId(videoId)
                        .stepIndex(i + 1)
                        .summary((String) s.get("summary"))
                        .startTime(BigDecimal.valueOf(
                                ((Number) s.get("start")).doubleValue()))
                        .endTime(BigDecimal.valueOf(
                                ((Number) s.get("end")).doubleValue()))
                        .originalText((String) s.get("text"))
                        .confidence(mapConfidence((String) s.get("confidence")))
                        .build();
                stepRepository.insert(step);
            }
        }

        // 更新视频状态和步骤数；处理完成后自动进入「待审核」
        video.setStatus(Video.STATUS_COMPLETED);
        video.setReviewStatus(Video.REVIEW_PENDING);
        video.setStepCount(steps != null ? steps.size() : 0);
        videoRepository.updateById(video);

        log.info("Saved {} steps for video {}", steps != null ? steps.size() : 0, videoId);
    }

    private String mapConfidence(String conf) {
        if (conf == null) return VideoStep.CONFIDENCE_LOW;
        return switch (conf.toLowerCase()) {
            case "high" -> VideoStep.CONFIDENCE_HIGH;
            case "medium" -> VideoStep.CONFIDENCE_MEDIUM;
            default -> VideoStep.CONFIDENCE_LOW;
        };
    }
}
