package com.videostep.locator.service.xunwei;

/**
 * 搜索到的视频候选（归一化，跨平台统一）。
 *
 * @param id        平台视频 ID（如 B 站 bvid）
 * @param url       可被 yt-dlp 下载的真实地址（喂给 /api/download/parse 或 /start）
 * @param title     标题（已清洗 HTML 标签）
 * @param uploader  UP 主
 * @param duration  时长（秒）
 * @param viewCount 播放量
 * @param thumbnail 封面 URL（已补全协议头）
 * @param platform  来源平台：bilibili / youtube / other
 */
public record VideoCandidate(
        String id,
        String url,
        String title,
        String uploader,
        double duration,
        long viewCount,
        String thumbnail,
        String platform) {
}
