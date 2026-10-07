package com.videostep.locator.service.xunwei;

import java.util.List;
import java.util.Map;

/**
 * 「寻味」一轮对话的结果。
 *
 * @param action  chat=纯聊天回复；search=搜索/推荐（含 videos 时已给出推荐）；error=出错
 * @param reply   给用户的回复文本
 * @param queries 需要搜索的关键词列表（action=search 时才有意义）
 * @param videos  推荐视频列表（Round2 后填充：title/url/uploader/duration/viewCount/thumbnail/reason）
 */
public record ChatResult(
        String action,
        String reply,
        List<Map<String, Object>> queries,
        List<Map<String, Object>> videos) {

    public ChatResult(String action, String reply, List<Map<String, Object>> queries) {
        this(action, reply, queries, List.of());
    }
}
