package com.videostep.locator.service.safety;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.FoodSafetyRule;
import com.videostep.locator.entity.FoodSafetyRuleCandidate;
import com.videostep.locator.entity.FoodSafetyRuleFeedback;
import com.videostep.locator.repository.FoodSafetyRuleCandidateRepository;
import com.videostep.locator.repository.FoodSafetyRuleFeedbackRepository;
import com.videostep.locator.repository.FoodSafetyRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则反馈自动降级任务（运营增强 D5）：定期统计各规则「无用率」，超过阈值自动降级
 * （RED→YELLOW / YELLOW→禁用），纠错进人工审核队列（food_safety_rule_candidates）。
 * 配置：safety.feedback.degrade-cron（默认每日 03:30）、degrade-min-total、degrade-rate。
 * 设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§1.5。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleFeedbackJob {

    private final FoodSafetyRuleRepository ruleRepository;
    private final FoodSafetyRuleFeedbackRepository feedbackRepository;
    private final FoodSafetyRuleCandidateRepository candidateRepository;

    @Value("${safety.feedback.degrade-min-total:3}")
    private int minTotal;

    @Value("${safety.feedback.degrade-rate:0.5}")
    private double degradeRate;

    @Scheduled(cron = "${safety.feedback.degrade-cron:0 30 3 * * *}")
    public void degrade() {
        List<FoodSafetyRule> rules = ruleRepository.selectList(
                new LambdaQueryWrapper<FoodSafetyRule>().eq(FoodSafetyRule::getEnabled, 1));
        int degraded = 0;
        for (FoodSafetyRule rule : rules) {
            try {
                List<FoodSafetyRuleFeedback> list = feedbackRepository.selectList(
                        new LambdaQueryWrapper<FoodSafetyRuleFeedback>()
                                .eq(FoodSafetyRuleFeedback::getRuleId, rule.getId()));
                if (list.size() < minTotal) continue;
                long useless = list.stream()
                        .filter(f -> f.getUseful() != null && f.getUseful() == 0).count();
                double rate = useless * 1.0 / list.size();
                if (rate < degradeRate) continue;
                if ("RED".equals(rule.getRiskLevel())) {
                    rule.setRiskLevel("YELLOW");
                    rule.setConfidence(Math.max(10, (rule.getConfidence() == null ? 70 : rule.getConfidence()) - 20));
                    ruleRepository.updateById(rule);
                    degraded++;
                    log.info("safety rule {} degraded RED→YELLOW (uselessRate={})", rule.getId(), rate);
                } else {
                    rule.setEnabled(0);
                    ruleRepository.updateById(rule);
                    degraded++;
                    log.info("safety rule {} disabled (uselessRate={})", rule.getId(), rate);
                }
                // 纠错进人工队列
                for (FoodSafetyRuleFeedback f : list) {
                    if (f.getComment() == null || f.getComment().isBlank()) continue;
                    FoodSafetyRuleCandidate c = FoodSafetyRuleCandidate.builder()
                            .category(rule.getCategory())
                            .riskLevel(rule.getRiskLevel())
                            .triggerType(rule.getTriggerType())
                            .triggerValue(rule.getTriggerValue())
                            .relatedFood(rule.getRelatedFood())
                            .title("复核：" + rule.getTitle())
                            .advice("用户纠错：" + f.getComment() + " | 原规则：" + rule.getAdvice())
                            .source(rule.getSource())
                            .sourceUrl(rule.getSourceUrl())
                            .status("pending")
                            .confidence(rule.getConfidence())
                            .build();
                    candidateRepository.insert(c);
                }
            } catch (Exception e) {
                log.warn("feedback degrade failed rule={}: {}", rule.getId(), e.getMessage());
            }
        }
        log.info("RuleFeedbackJob degraded {} rules", degraded);
    }
}
