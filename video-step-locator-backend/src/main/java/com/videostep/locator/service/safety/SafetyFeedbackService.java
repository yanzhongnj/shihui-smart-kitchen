package com.videostep.locator.service.safety;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.FoodSafetyRule;
import com.videostep.locator.entity.FoodSafetyRuleFeedback;
import com.videostep.locator.repository.FoodSafetyRuleFeedbackRepository;
import com.videostep.locator.repository.FoodSafetyRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则反馈（运营增强 D5 反馈闭环）：记录用户「有用/无用/纠错」，供 RuleFeedbackJob 统计降级。
 * 设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§1.5。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SafetyFeedbackService {

    private final FoodSafetyRuleFeedbackRepository feedbackRepository;
    private final FoodSafetyRuleRepository ruleRepository;

    public void record(Long userId, Long ruleId, Integer useful, String comment) {
        if (ruleId == null) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        FoodSafetyRule rule = ruleRepository.selectById(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("规则不存在或已停用");
        }
        FoodSafetyRuleFeedback f = FoodSafetyRuleFeedback.builder()
                .ruleId(ruleId)
                .userId(userId)
                .useful(useful)
                .comment(comment)
                .build();
        feedbackRepository.insert(f);
        log.info("safety feedback rule={} user={} useful={} comment={}", ruleId, userId, useful, comment);
    }

    /** 单规则反馈统计 */
    public Map<String, Object> summary(Long ruleId) {
        List<FoodSafetyRuleFeedback> list = feedbackRepository.selectList(
                new LambdaQueryWrapper<FoodSafetyRuleFeedback>().eq(FoodSafetyRuleFeedback::getRuleId, ruleId));
        long total = list.size();
        long useless = list.stream().filter(f -> f.getUseful() != null && f.getUseful() == 0).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", ruleId);
        m.put("total", total);
        m.put("useful", list.stream().filter(f -> f.getUseful() != null && f.getUseful() == 1).count());
        m.put("useless", useless);
        m.put("uselessRate", total == 0 ? 0 : Math.round(useless * 1000.0 / total) / 10.0);
        return m;
    }
}
