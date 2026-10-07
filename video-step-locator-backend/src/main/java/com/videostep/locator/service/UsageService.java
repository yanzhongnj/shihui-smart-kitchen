package com.videostep.locator.service;

import com.videostep.locator.entity.UsageRecord;
import com.videostep.locator.exception.QuotaExceededException;
import com.videostep.locator.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用量记录 + 游客配额。
 * 游客：按 device_id 对 解析/预览/下载 限次/限量（配额用尽抛 QuotaExceededException → 429）。
 * 注册用户：累计流量（bytes）供按流量计费（公式待定）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UsageService {

    private final UsageRecordRepository usageRepository;

    public static final String TYPE_PARSE = "parse";
    public static final String TYPE_PREVIEW = "preview";
    public static final String TYPE_DOWNLOAD = "download";
    public static final String TYPE_PROCESS = "process";
    public static final String TYPE_PHOTO_CHECKIN = "photo_checkin";

    // 游客配额（可按需调整）
    private static final int GUEST_MAX_PARSE = 10;
    private static final int GUEST_MAX_PREVIEW = 20;
    private static final int GUEST_MAX_DOWNLOAD_COUNT = 5;
    private static final long GUEST_MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024; // 500MB

    /** 记录一次用量；注册用户 user_id 非空，游客 device_id 非空 */
    public UsageRecord record(Long userId, String deviceId, String actionType, Long videoId, long bytes) {
        UsageRecord r = UsageRecord.builder()
                .userId(userId)
                .deviceId(deviceId)
                .actionType(actionType)
                .videoId(videoId)
                .bytes(bytes)
                .build();
        usageRepository.insert(r);
        return r;
    }

    /** 游客配额检查；注册用户（有 userId）不限制 */
    public void assertGuestQuota(Long userId, String deviceId, String actionType, long bytes) {
        if (userId != null) return; // 注册用户不限（计费另算）
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("游客请求缺少设备标识 X-Device-Id");
        }
        switch (actionType) {
            case TYPE_PARSE -> {
                if (usageRepository.countByDeviceAndType(deviceId, TYPE_PARSE) >= GUEST_MAX_PARSE)
                    throw new QuotaExceededException("游客解析次数已达上限，请注册后继续");
            }
            case TYPE_PREVIEW -> {
                if (usageRepository.countByDeviceAndType(deviceId, TYPE_PREVIEW) >= GUEST_MAX_PREVIEW)
                    throw new QuotaExceededException("游客预览次数已达上限，请注册后继续");
            }
            case TYPE_DOWNLOAD -> {
                long used = usageRepository.sumBytesByDeviceAndType(deviceId, TYPE_DOWNLOAD);
                long count = usageRepository.countByDeviceAndType(deviceId, TYPE_DOWNLOAD);
                if (count >= GUEST_MAX_DOWNLOAD_COUNT)
                    throw new QuotaExceededException("游客下载次数已达上限，请注册后继续");
                if (used + bytes > GUEST_MAX_DOWNLOAD_BYTES)
                    throw new QuotaExceededException("游客下载流量已达上限，请注册后继续");
            }
            default -> { }
        }
    }
}
