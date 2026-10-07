package com.videostep.locator.service.xunwei;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「寻味」多源搜索调度：遍历 LLM 生成的查询，顺序调用各源工具，
 * 合并去重（按 url）、统一上限，任一路径失败自动降级不影响其它源。
 *
 * 平台路由：bilibili→仅 B 站；youtube→仅 yt-dlp；auto→B 站 +（启用了 yt-dlp 时再加）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class XunweiSearchService {

    private static final int MAX_CANDIDATES = 20;

    private final BilibiliSearchTool bilibiliTool;
    private final YtDlpSearchTool ytDlpTool;

    @Value("${xunwei.search.enable-ytdlp:false}")
    private boolean enableYtdlp;

    public List<VideoCandidate> search(List<Map<String, Object>> queries) {
        Map<String, VideoCandidate> merged = new LinkedHashMap<>();
        for (Map<String, Object> q : queries) {
            String keyword = q.get("keyword") == null ? "" : q.get("keyword").toString();
            if (keyword.isBlank()) continue;
            String platform = q.get("platform") == null ? "auto" : q.get("platform").toString();
            int count = q.get("count") instanceof Number num
                    ? Math.max(1, Math.min(10, num.intValue())) : 5;

            // B 站始终可用；auto/bilibili 直连 B 站；youtube 查询在未启用 yt-dlp 时回退 B 站
            boolean toBili = "bilibili".equals(platform) || "auto".equals(platform)
                    || ("youtube".equals(platform) && !enableYtdlp);
            // yt-dlp 仅当显式开启（国内默认关，避免 YouTube 出网超时拖慢）
            boolean toYt = enableYtdlp
                    && ("youtube".equals(platform) || "auto".equals(platform));
            if (toBili) {
                try {
                    merge(merged, bilibiliTool.search(keyword, count));
                } catch (Exception e) {
                    log.warn("[寻味] B站搜索失败 keyword={}: {}", keyword, e.getMessage());
                }
            }
            if (toYt) {
                try {
                    merge(merged, ytDlpTool.search(keyword, count));
                } catch (Exception e) {
                    log.warn("[寻味] yt-dlp 搜索失败 keyword={}: {}", keyword, e.getMessage());
                }
            }
        }
        List<VideoCandidate> all = new ArrayList<>(merged.values());
        if (all.size() > MAX_CANDIDATES) {
            all = new ArrayList<>(all.subList(0, MAX_CANDIDATES));
        }
        log.info("[寻味] 搜索汇总 queries={} candidates={}", queries.size(), all.size());
        return all;
    }

    private void merge(Map<String, VideoCandidate> map, List<VideoCandidate> list) {
        for (VideoCandidate c : list) {
            if (c.url() != null) {
                map.putIfAbsent(c.url(), c);
            }
        }
    }
}
