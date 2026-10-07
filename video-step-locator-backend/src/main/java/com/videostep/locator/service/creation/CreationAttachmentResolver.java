package com.videostep.locator.service.creation;

import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.VideoStep;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.VideoStepRepository;
import com.videostep.locator.service.YtDlpService;
import com.videostep.locator.service.xunwei.XunweiVisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 酿味多源附件解析（P1）：把附件解析成可注入 LLM 上下文的文本。
 * 图片 → qwen-vl 识菜（复用 XunweiVisionService）；系统视频引用 → recipe_metadata/video_steps 摘要；
 * 外部 URL → yt-dlp 解析。解析失败不阻塞会话（标记后继续）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationAttachmentResolver {

    private final XunweiVisionService visionService;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final VideoStepRepository videoStepRepository;
    private final YtDlpService ytDlpService;

    /** 图片 → 食材识别文本 */
    public String resolveImage(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) {
            return "[图片识别] 图片为空，请重试";
        }
        try {
            List<String> ingredients = visionService.recognizeIngredients(bytes, filename);
            if (ingredients == null || ingredients.isEmpty()) {
                return "[图片识别] 未能识别出食材，请文字补充";
            }
            return "[图片识别的食材] " + String.join("、", ingredients);
        } catch (Exception e) {
            log.warn("[酿味] 图片识别失败: {}", e.getMessage());
            return "[图片识别] 图片解析失败，请文字补充食材";
        }
    }

    /** 系统内视频引用 → 菜谱/步骤摘要 */
    public String resolveVideoRef(Long videoId) {
        if (videoId == null) return "[视频引用] 缺少视频ID";
        StringBuilder sb = new StringBuilder("[引用系统视频");
        RecipeMetadata meta = recipeMetadataRepository.findByVideoId(videoId);
        if (meta != null) {
            sb.append("《").append(meta.getTitle()).append("》");
            if (meta.getMainIngredients() != null && !meta.getMainIngredients().isBlank()) {
                sb.append(" 主料:").append(meta.getMainIngredients());
            }
            if (meta.getTasteTags() != null && !meta.getTasteTags().isBlank()) {
                sb.append(" 口味:").append(meta.getTasteTags());
            }
        }
        List<VideoStep> steps = videoStepRepository.findByVideoIdOrderByStepIndexAsc(videoId);
        if (!steps.isEmpty()) {
            String stepsText = steps.stream()
                    .limit(8)
                    .map(s -> s.getStepIndex() + "." + s.getSummary())
                    .collect(Collectors.joining(" "));
            sb.append(" 步骤:").append(stepsText);
        }
        sb.append("]");
        return sb.toString();
    }

    /** 外部 URL → yt-dlp 摘要 */
    public String resolveUrl(String url) {
        if (url == null || url.isBlank()) return "[链接解析] 链接为空";
        try {
            YtDlpService.ParseResult pr = ytDlpService.parse(url.trim());
            StringBuilder sb = new StringBuilder("[外部链接解析");
            if (pr.getPageTitle() != null && !pr.getPageTitle().isBlank()) {
                sb.append(" 标题:").append(pr.getPageTitle());
            }
            if (pr.getEntries() != null && !pr.getEntries().isEmpty()) {
                YtDlpService.ParsedEntry e = pr.getEntries().get(0);
                sb.append(" 视频:").append(e.getTitle());
                if (e.getDuration() != null && e.getDuration() > 0) {
                    sb.append(" 时长:").append(Math.round(e.getDuration())).append("秒");
                }
                if (e.getUploader() != null && !e.getUploader().isBlank()) {
                    sb.append(" UP主:").append(e.getUploader());
                }
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[酿味] URL 解析失败 url={}: {}", url, e.getMessage());
            return "[外部链接解析] 链接解析失败：" + url;
        }
    }
}
