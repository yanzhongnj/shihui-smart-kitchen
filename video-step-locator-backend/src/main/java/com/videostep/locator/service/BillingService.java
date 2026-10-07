package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.UsageRecord;
import com.videostep.locator.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计费摘要（公式待定，先提供钩子）：
 * 费用 = 累计流量(GB) × ratePerGb；ratePerGb 通过 app.billing.rate-per-gb 配置。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingService {

    private final UsageRecordRepository usageRepository;

    @Value("${app.billing.rate-per-gb:0}")
    private double ratePerGb;

    public Map<String, Object> summary(Long userId) {
        long totalBytes = usageRepository.sumBytesByUser(userId);
        double totalGb = totalBytes / 1024.0 / 1024.0 / 1024.0;
        Map<String, Object> m = new HashMap<>();
        m.put("totalBytes", totalBytes);
        m.put("totalGb", Math.round(totalGb * 1000) / 1000.0);
        m.put("ratePerGb", ratePerGb);
        m.put("estimatedCost", Math.round(totalGb * ratePerGb * 100) / 100.0);
        m.put("currency", "CNY");
        m.put("formula", "费用 = 累计流量(GB) × ratePerGb（公式待定，ratePerGb 可在配置调整）");
        return m;
    }

    /**
     * 分项统计（待办 #16）：按 actionType 聚合条数 + 数量。
     * bytes 语义因类型而异：videogen=生成秒数、tts=字符数、其余=字节。
     * userId 为空 = 全平台。
     */
    public Map<String, Object> summaryByType(Long userId) {
        LambdaQueryWrapper<UsageRecord> qw = new LambdaQueryWrapper<UsageRecord>()
                .eq(userId != null, UsageRecord::getUserId, userId);
        List<UsageRecord> all = usageRepository.selectList(qw);
        Map<String, Long> countByType = new LinkedHashMap<>();
        Map<String, Long> amountByType = new LinkedHashMap<>();
        long totalAmount = 0;
        for (UsageRecord u : all) {
            String t = u.getActionType() == null ? "unknown" : u.getActionType();
            countByType.merge(t, 1L, Long::sum);
            long v = u.getBytes() == null ? 0 : u.getBytes();
            amountByType.merge(t, v, Long::sum);
            totalAmount += v;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("totalRecords", all.size());
        m.put("totalAmount", totalAmount);
        m.put("byType", countByType);
        m.put("amountByType", amountByType);
        return m;
    }

    /** B 端：企业维度用量摘要（按企业全部成员的用量聚合） */
    public Map<String, Object> summaryOrg(Long orgId) {
        long totalBytes = usageRepository.sumBytesByOrg(orgId);
        double totalGb = totalBytes / 1024.0 / 1024.0 / 1024.0;
        Map<String, Object> m = new HashMap<>();
        m.put("totalBytes", totalBytes);
        m.put("totalGb", Math.round(totalGb * 1000) / 1000.0);
        m.put("ratePerGb", ratePerGb);
        m.put("estimatedCost", Math.round(totalGb * ratePerGb * 100) / 100.0);
        m.put("currency", "CNY");
        return m;
    }
}
