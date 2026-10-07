package com.videostep.locator.service.creation;

import com.videostep.locator.entity.UsageRecord;
import com.videostep.locator.exception.QuotaExceededException;
import com.videostep.locator.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 酿味视频生成按秒计费 + 每日配额（P3）。
 * 计费记录复用 usage_records（actionType='videogen'，bytes 语义=生成秒数）。
 * 仅真实引擎计费（mock 免费，不计配额）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationQuotaService {

    public static final String ACTION_VIDEOGEN = "videogen";
    /** 酱汁设计（按次，纯 LLM 低成本，P0） */
    public static final String ACTION_SAUCE = "sauce";
    /** 效果图生成（按次，P1） */
    public static final String ACTION_IMAGEGEN = "imagegen";

    private final UsageRecordRepository usageRepository;

    @Value("${creation.rate-limit.daily-per-user-sec:120}")
    private int dailySec;

    @Value("${creation.rate-limit.daily-per-user:10}")
    private int dailyCount;

    @Value("${creation.rate-limit.daily-per-user-sauce:20}")
    private int dailySauceCount;

    @Value("${creation.rate-limit.daily-per-user-image:5}")
    private int dailyImageCount;

    /** 生成前校验配额（超限抛 QuotaExceededException → 429） */
    public void assertCanGenerate(Long userId, int segments, int segmentSec) {
        long needSec = (long) segments * segmentSec;
        long usedSec = usageRepository.sumByUserAndTypeToday(userId, ACTION_VIDEOGEN);
        long count = usageRepository.countByUserAndTypeToday(userId, ACTION_VIDEOGEN);
        if (count + 1 > dailyCount) {
            throw new QuotaExceededException("今日视频生成次数已达上限（" + dailyCount + " 次）");
        }
        if (usedSec + needSec > dailySec) {
            throw new QuotaExceededException("今日视频生成秒数已达上限（" + dailySec + " 秒），请明日再试");
        }
    }

    /** 生成成功后记录用量（按生成秒数） */
    public void record(Long userId, Long creationId, int segments, int segmentSec) {
        long sec = (long) segments * segmentSec;
        UsageRecord r = UsageRecord.builder()
                .userId(userId)
                .actionType(ACTION_VIDEOGEN)
                .videoId(creationId)
                .bytes(sec)
                .build();
        usageRepository.insert(r);
        log.info("[酿味] 记录视频生成用量 userId={} creationId={} sec={}", userId, creationId, sec);
    }

    /** 今日用量查询 */
    public Map<String, Object> todayUsage(Long userId) {
        long usedSec = usageRepository.sumByUserAndTypeToday(userId, ACTION_VIDEOGEN);
        long count = usageRepository.countByUserAndTypeToday(userId, ACTION_VIDEOGEN);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("usedSeconds", usedSec);
        r.put("maxSeconds", dailySec);
        r.put("usedCount", count);
        r.put("maxCount", dailyCount);
        return r;
    }

    // ---------------- 酱汁设计 / 效果图（按次，P0/P1） ----------------

    /** 酱汁设计前校验每日次数（超限抛 QuotaExceededException → 429） */
    public void assertCanDesignSauce(Long userId) {
        long count = usageRepository.countByUserAndTypeToday(userId, ACTION_SAUCE);
        if (count + 1 > dailySauceCount) {
            throw new QuotaExceededException("今日酱汁设计次数已达上限（" + dailySauceCount + " 次）");
        }
    }

    /** 酱汁设计确认后记录（按次，bytes=0） */
    public void recordSauce(Long userId, Long creationId) {
        UsageRecord r = UsageRecord.builder()
                .userId(userId)
                .actionType(ACTION_SAUCE)
                .videoId(creationId)
                .bytes(0L)
                .build();
        usageRepository.insert(r);
        log.info("[酿味] 记录酱汁设计用量 userId={} creationId={}", userId, creationId);
    }

    /** 效果图生成前校验每日次数（超限抛 QuotaExceededException → 429） */
    public void assertCanGenerateImage(Long userId) {
        long count = usageRepository.countByUserAndTypeToday(userId, ACTION_IMAGEGEN);
        if (count + 1 > dailyImageCount) {
            throw new QuotaExceededException("今日效果图生成次数已达上限（" + dailyImageCount + " 张）");
        }
    }

    /** 效果图生成成功后记录（按次，bytes=0；cost 记在 ai_image_tasks.cost 分） */
    public void recordImage(Long userId, Long creationId) {
        UsageRecord r = UsageRecord.builder()
                .userId(userId)
                .actionType(ACTION_IMAGEGEN)
                .videoId(creationId)
                .bytes(0L)
                .build();
        usageRepository.insert(r);
        log.info("[酿味] 记录效果图生成用量 userId={} creationId={}", userId, creationId);
    }

    /** 今日效果图配额用量（APP 展示） */
    public Map<String, Object> todayImageUsage(Long userId) {
        long count = usageRepository.countByUserAndTypeToday(userId, ACTION_IMAGEGEN);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("usedCount", count);
        r.put("maxCount", dailyImageCount);
        return r;
    }
}
