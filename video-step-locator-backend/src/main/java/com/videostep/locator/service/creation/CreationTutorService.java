package com.videostep.locator.service.creation;

import com.videostep.locator.entity.AiCreation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酿味图文跟做 + 计时器（无视频时的兜底表达；TTS 已按 v0.4 决策不做）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationTutorService {

    private final CreationChatService chatService;

    /**
     * 生成图文跟做视图：步骤卡数据（summary/detail）+ 计时器（timer_min 步骤倒计时）。
     */
    public Map<String, Object> cook(Long userId, Long creationId) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        List<Map<String, Object>> steps = CreationJsonUtil.parseSteps(c.getSteps());
        List<Map<String, Object>> tutorSteps = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", s.get("index"));
            m.put("summary", s.get("summary"));
            m.put("detail", s.get("detail"));
            Object timer = s.get("timer_min");
            m.put("timer_min", timer == null ? 0 : timer);
            m.put("hasTimer", timer != null && ((Number) timer).intValue() > 0);
            tutorSteps.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("creationId", c.getId());
        r.put("title", c.getTitle());
        r.put("disclaimer", "AI 创作菜谱仅供参考，请按实际食材与火候操作");
        r.put("steps", tutorSteps);
        return r;
    }
}
