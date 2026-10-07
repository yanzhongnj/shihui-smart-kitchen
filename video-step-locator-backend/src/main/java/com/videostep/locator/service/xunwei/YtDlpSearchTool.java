package com.videostep.locator.service.xunwei;

import com.videostep.locator.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * yt-dlp 搜索工具（ytsearchN: 前缀）。
 *
 * 用于 YouTube 等站点的搜索；国内环境 YouTube 可能出网受限，
 * 由 XunweiSearchService 统一捕获异常并降级到其它源。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class YtDlpSearchTool {

    private final YtDlpService ytDlpService;

    public List<VideoCandidate> search(String keyword, int count) throws Exception {
        List<VideoCandidate> out = new ArrayList<>();
        YtDlpService.ParseResult r = ytDlpService.search(keyword, count);
        for (YtDlpService.ParsedEntry e : r.getEntries()) {
            if (e.getUrl() == null || !e.getUrl().startsWith("http")) continue;
            out.add(new VideoCandidate(
                    e.getId(),
                    e.getUrl(),
                    e.getTitle(),
                    e.getUploader(),
                    e.getDuration() == null ? 0 : e.getDuration(),
                    e.getViewCount() == null ? 0 : e.getViewCount(),
                    e.getThumbnail(),
                    "youtube"));
        }
        log.info("[寻味] yt-dlp 搜索 keyword={} results={}", keyword, out.size());
        return out;
    }
}
