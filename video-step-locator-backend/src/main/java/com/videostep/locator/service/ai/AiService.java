package com.videostep.locator.service.ai;

import com.videostep.locator.entity.VideoStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AiService {

    public AiService() {
        log.info("AiService initialized (rule-based mode)");
        log.info("DeepSeek LLM configured via video.processing.llm-* settings");
    }

    public String optimizeStepSummary(String originalSummary, String originalText) {
        if (originalSummary != null && originalSummary.length() > 20) {
            return originalSummary.substring(0, 17) + "...";
        }
        return originalSummary != null ? originalSummary : "未命名步骤";
    }

    public String askQuestion(String question, List<VideoStep> steps) {
        String q = question.replaceAll("[?]", "");
        for (VideoStep step : steps) {
            if (step.getSummary() != null && step.getSummary().contains(q)) {
                return String.format("第%d步: %s", step.getStepIndex(), step.getSummary());
            }
        }
        return "未找到相关信息";
    }

    public String generateVideoSummary(List<VideoStep> steps) {
        if (steps.isEmpty()) return "暂无步骤数据";
        return String.format("共%d步: %s", steps.size(), steps.get(0).getSummary());
    }

    public Map<String, Object> parseVoiceCommand(String text, int totalSteps) {
        return Map.of("action", "UNKNOWN", "params", Map.of());
    }
}
