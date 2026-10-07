package com.videostep.locator.service.xunwei;

import com.videostep.locator.entity.XunweiMemory;
import com.videostep.locator.repository.XunweiMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * P2 记忆衰减任务：长期未命中的画像逐步降置信度，低于阈值软删除（清理过期偏好）。
 * - 显式设置（explicit）不衰减（用户主动填写，最高优先级）；
 * - 行为记忆（behavior）衰减更慢（真实行为信号更可信）；
 * - 对话抽取画像（profile）超过衰减周期后每次降 15，低于阈值即软删除。
 * 配置：xunwei.memory.decay-cron（默认每日 03:00）、decay-profile-days、decay-behavior-days、decay-threshold。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryDecayJob {

    private static final int BATCH_SIZE = 500;
    private static final int DECAY_STEP = 15;

    private final XunweiMemoryRepository memoryRepository;

    @Value("${xunwei.memory.decay-profile-days:30}")
    private int profileDecayDays;

    @Value("${xunwei.memory.decay-behavior-days:90}")
    private int behaviorDecayDays;

    @Value("${xunwei.memory.decay-threshold:20}")
    private int decayThreshold;

    @Scheduled(cron = "${xunwei.memory.decay-cron:0 0 3 * * *}")
    public void decay() {
        int decayed = 0, removed = 0, offset = 0;
        try {
            List<XunweiMemory> page;
            while (!(page = memoryRepository.findActiveGlobal(offset, BATCH_SIZE)).isEmpty()) {
                for (XunweiMemory m : page) {
                    if (decayOne(m)) removed++; else decayed++;
                }
                offset += BATCH_SIZE;
            }
            log.info("[寻味] 记忆衰减完成 decayed={} removed={}", decayed, removed);
        } catch (Exception e) {
            log.warn("[寻味] 记忆衰减任务异常: {}", e.getMessage());
        }
    }

    /** 处理单条记忆；返回 true=已软删除，false=降置信或未处理 */
    private boolean decayOne(XunweiMemory m) {
        if (m.getDeleted() != null && m.getDeleted() == 1) return false;
        if ("explicit".equals(m.getSource())) return false; // 显式设置不衰减
        LocalDateTime updated = m.getUpdatedAt();
        if (updated == null) return false;
        long ageDays = Duration.between(updated, LocalDateTime.now()).toDays();
        if (ageDays < 0) ageDays = 0;
        int decayAfter = "behavior".equals(m.getMemType()) ? behaviorDecayDays : profileDecayDays;
        if (ageDays < decayAfter) return false;

        int conf = m.getConfidence() == null ? 50 : m.getConfidence();
        int newConf = Math.max(0, conf - DECAY_STEP);
        if (newConf < decayThreshold) {
            memoryRepository.softDeleteById(m.getId()); // 软删 + 记录 deleted_at（MP 逻辑删除）
            log.info("[寻味] 记忆衰减-删除 userId={} key={} conf={} age={}d",
                    m.getUserId(), m.getMemKey(), conf, ageDays);
            return true;
        }
        m.setConfidence(newConf);
        memoryRepository.updateById(m);
        log.info("[寻味] 记忆衰减-降置信 userId={} key={} conf={}->{} age={}d",
                m.getUserId(), m.getMemKey(), conf, newConf, ageDays);
        return false;
    }
}
