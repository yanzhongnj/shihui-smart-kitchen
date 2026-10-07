package com.videostep.locator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.exception.PipelineException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 封装 yt-dlp 可执行文件的解析与下载调用。
 * 参考 PythonPipelineRunner 的 ProcessBuilder + 流读取模式，使用 List 传参（无 shell 注入）。
 */
@Service
@Slf4j
public class YtDlpService {

    @Value("${ytdlp.path}")
    private String ytDlpPath;

    @Value("${ytdlp.format}")
    private String format;

    @Value("${ytdlp.merge-format}")
    private String mergeFormat;

    @Value("${ytdlp.parse-timeout-sec:30}")
    private long parseTimeoutSec;

    /** 额外参数（逗号分隔），如代理：--proxy,http://127.0.0.1:7890 */
    @Value("${ytdlp.extra-args:}")
    private String extraArgs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("\\[download]\\s+(\\d+(?:\\.\\d+)?)%");

    /** 运行中的下载进程（用于取消） */
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();

    // ==================== 解析链接 ====================

    /**
     * 解析链接：单视频页或合集/播放列表/多P 页面，返回归一化条目列表。
     */
    public ParseResult parse(String url) throws Exception {
        List<String> command = List.of(
                ytDlpPath, "-J", "--flat-playlist", "--no-warnings",
                "--no-check-certificates", url);

        ProcessResult r = run(command, parseTimeoutSec, null, null);
        if (r.exitCode != 0) {
            throw friendlyParseError(r.text);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(r.text);
        } catch (Exception e) {
            // 兜底：若输出为 GBK 编码则重新解码后解析
            root = objectMapper.readTree(new String(r.bytes, Charset.forName("GBK")));
        }
        if (root == null || root.isNull()) {
            throw new PipelineException("解析结果为空");
        }

        ParseResult result = new ParseResult();
        boolean hasEntries = root.has("entries") && root.path("entries").isArray();
        String type = root.path("_type").asText("video");
        if ("playlist".equals(type) || hasEntries) {
            result.setType("playlist");
            result.setPageTitle(root.path("title").asText(""));
            List<ParsedEntry> list = new ArrayList<>();
            for (JsonNode e : root.path("entries")) {
                if (e == null || e.isNull()) continue;
                ParsedEntry pe = toEntry(e);
                if (pe.getUrl() == null || !pe.getUrl().startsWith("http")) {
                    String rawUrl = e.path("url").asText(null);
                    if (rawUrl != null) {
                        pe.setUrl(resolveEntryUrl(root.path("webpage_url").asText(url), rawUrl, url));
                    }
                }
                if (pe.getUrl() != null) list.add(pe);
            }
            result.setCount(list.size());
            result.setEntries(list);
        } else {
            result.setType("video");
            result.setPageTitle(root.path("title").asText(""));
            result.setCount(1);
            ParsedEntry single = toEntry(root);
            if (single.getUrl() == null) single.setUrl(url);
            result.setEntries(List.of(single));
        }
        return result;
    }

    /**
     * 将 yt-dlp 解析失败输出映射为友好中文提示（避免把英文技术错误直接抛给用户）。
     * 常见场景：非视频链接 / 站点要求人机验证（如百度 wappass captcha）/ 被站点拦截。
     */
    private PipelineException friendlyParseError(String rawText) {
        String lower = (rawText == null ? "" : rawText).toLowerCase(Locale.ROOT);
        if (lower.contains("unsupported url") || lower.contains("captcha")
                || lower.contains("human verification") || lower.contains("recaptcha")
                || lower.contains("wappass")) {
            return new PipelineException("该链接不是有效的视频链接，或站点需要登录/人机验证后才能访问");
        }
        if (lower.contains("http error 403") || lower.contains("403 forbidden")) {
            return new PipelineException("该链接被站点拦截（需要登录或人机验证），无法解析");
        }
        if (lower.contains("unable to download webpage") || lower.contains("connection error")) {
            return new PipelineException("无法访问该链接，请检查链接是否有效或稍后重试");
        }
        return new PipelineException("链接解析失败: " + lastLine(rawText));
    }

    /** 将直链下载失败输出映射为友好中文提示（直链必须是真实媒体地址，不能是页面/验证码链接）。 */
    private PipelineException friendlyDownloadError(String rawText) {
        String lower = (rawText == null ? "" : rawText).toLowerCase(Locale.ROOT);
        if (lower.contains("wappass") || lower.contains("captcha")
                || lower.contains("unsupported url") || lower.contains("human verification")
                || lower.contains("recaptcha")) {
            return new PipelineException("下载失败：该链接不是有效的视频直链，或站点需要登录/人机验证后才能访问");
        }
        if (lower.contains("http error 403") || lower.contains("403 forbidden")) {
            return new PipelineException("下载失败：该链接被站点拦截（需要登录/人机验证或防盗链 Referer）");
        }
        if (lower.contains("unable to download webpage") || lower.contains("connection error")) {
            return new PipelineException("下载失败：无法访问该链接，请检查网络或链接是否有效");
        }
        return new PipelineException("下载失败: " + lastLine(rawText));
    }

    /**
     * 搜索：yt-dlp ytsearchN: 前缀，返回归一化条目列表（结构与 parse 一致）。
     * 供寻味/语音等使用；国内环境可能出网受限，由调用方统一捕获异常降级。
     */
    public ParseResult search(String keyword, int count) throws Exception {
        if (keyword == null || keyword.isBlank()) {
            throw new PipelineException("搜索关键字不能为空");
        }
        int n = count > 0 ? count : 10;
        String target = "ytsearch" + n + ":" + keyword;
        List<String> command = List.of(
                ytDlpPath, "-J", "--flat-playlist", "--no-warnings",
                "--no-check-certificates", target);

        ProcessResult r = run(command, parseTimeoutSec, null, null);
        if (r.exitCode != 0) {
            throw new PipelineException("搜索失败 (exit=" + r.exitCode + "): " + lastLine(r.text));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(r.text);
        } catch (Exception e) {
            root = objectMapper.readTree(new String(r.bytes, Charset.forName("GBK")));
        }

        ParseResult result = new ParseResult();
        result.setType("playlist");
        result.setPageTitle("搜索结果: " + keyword);
        List<ParsedEntry> list = new ArrayList<>();
        if (root != null && root.path("entries").isArray()) {
            for (JsonNode e : root.path("entries")) {
                if (e == null || e.isNull()) continue;
                ParsedEntry pe = toEntry(e);
                if (pe.getUrl() == null || !pe.getUrl().startsWith("http")) continue;
                list.add(pe);
            }
        }
        result.setCount(list.size());
        result.setEntries(list);
        return result;
    }

    private ParsedEntry toEntry(JsonNode n) {
        ParsedEntry e = new ParsedEntry();
        e.setId(n.path("id").asText(null));
        e.setTitle(n.path("title").asText(null));
        String url = n.path("webpage_url").asText(null);
        if (url == null) url = n.path("url").asText(null);
        e.setUrl(url);
        if (n.path("duration").isNumber()) e.setDuration(n.path("duration").asDouble());
        e.setUploader(n.path("uploader").asText(null));
        e.setThumbnail(n.path("thumbnail").asText(null));
        if (n.path("view_count").isNumber()) e.setViewCount(n.path("view_count").asLong());
        return e;
    }

    private String resolveEntryUrl(String baseUrl, String entryUrl, String fallback) {
        try {
            return new URI(baseUrl).resolve(entryUrl).toString();
        } catch (Exception e) {
            return fallback;
        }
    }

    // ==================== 直连地址解析（在线预览用） ====================

    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile(
            "\\.(mp4|m4v|webm|flv|mkv|mov|avi|ts|m3u8|mp3|m4a|aac)(\\?.*)?$",
            Pattern.CASE_INSENSITIVE);

    /** 判断 URL 是否看起来是可直接播放的媒体文件地址（而非网页/分享页） */
    public static boolean isDirectMediaUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return MEDIA_URL_PATTERN.matcher(lower).find()
                || lower.contains("mime=video") || lower.contains("content-type=video")
                || lower.contains(".m3u8") || lower.contains("videoplayback");
    }

    /** 解析结果：直连媒体地址 + 播放所需请求头（Referer/UA 等防盗链头） */
    public record ResolvedMedia(String url, Map<String, String> headers) {}

    /**
     * 通过 yt-dlp 解析视频页/分享链接的直连播放地址与所需请求头（供在线预览代理使用）。
     * 优先 -J 全量提取（能拿到 http_headers 防盗链头，如 B站 Referer），失败兜底 -g。
     * 返回 null 表示解析失败。
     */
    public ResolvedMedia resolveMedia(String url) {
        try {
            List<String> command = new ArrayList<>(List.of(
                    ytDlpPath, "-J", "--no-playlist", "--no-warnings",
                    "--no-check-certificates"));
            // 额外参数（如代理 --proxy,http://...）
            if (extraArgs != null && !extraArgs.isBlank()) {
                for (String arg : extraArgs.split(",")) {
                    if (!arg.isBlank()) command.add(arg.trim());
                }
            }
            command.add(url);
            ProcessResult r = run(command, parseTimeoutSec, null, null);
            if (r.exitCode == 0) {
                JsonNode root = objectMapper.readTree(r.text);
                JsonNode formats = root.path("formats");
                JsonNode chosen = null;
                if (formats.isArray() && formats.size() > 0) {
                    JsonNode progressiveAvc = null, progressive = null, avcVideo = null, anyVideo = null;
                    for (JsonNode f : formats) {
                        String acodec = f.path("acodec").asText("none");
                        String vcodec = f.path("vcodec").asText("none");
                        boolean hasAudio = !"none".equals(acodec);
                        boolean hasVideo = !"none".equals(vcodec);
                        boolean isAvc = vcodec.startsWith("avc1");
                        if (hasAudio && hasVideo) {
                            if (isAvc && progressiveAvc == null) progressiveAvc = f;
                            if (progressive == null) progressive = f;
                        } else if (hasVideo) {
                            if (isAvc && avcVideo == null) avcVideo = f;
                            if (anyVideo == null) anyVideo = f;
                        }
                    }
                    // 选格式优先级（ExoPlayer/模拟器兼容性）：
                    // 1) H.264 音视频合一(最理想) 2) 任意音视频合一 3) H.264 纯视频 4) 任意纯视频 5) 最后一个
                    if (progressiveAvc != null) chosen = progressiveAvc;
                    else if (progressive != null) chosen = progressive;
                    else if (avcVideo != null) chosen = avcVideo;
                    else if (anyVideo != null) chosen = anyVideo;
                    else chosen = formats.get(formats.size() - 1);
                }
                if (chosen != null) {
                    String mediaUrl = chosen.path("url").asText(null);
                    if (mediaUrl == null) mediaUrl = root.path("url").asText(null);
                    if (mediaUrl != null && !mediaUrl.isBlank() && mediaUrl.startsWith("http")) {
                        Map<String, String> headers = new HashMap<>();
                        JsonNode hh = chosen.path("http_headers");
                        if (hh.isObject()) {
                            hh.fields().forEachRemaining(en -> headers.put(en.getKey(), en.getValue().asText()));
                        }
                        return new ResolvedMedia(mediaUrl, headers);
                    }
                }
            }
            // 兜底：-g 只取直连地址（无防盗链头）
            String direct = getDirectUrl(url);
            if (direct != null) return new ResolvedMedia(direct, Map.of());
            return null;
        } catch (Exception e) {
            log.warn("resolveMedia error: {}", e.getMessage());
            return null;
        }
    }

    /** 通过 yt-dlp -g 解析直连播放地址（兜底用） */
    public String getDirectUrl(String url) {
        try {
            List<String> command = new ArrayList<>(List.of(
                    ytDlpPath, "-g", "--no-playlist", "--no-warnings",
                    "--no-check-certificates"));
            if (extraArgs != null && !extraArgs.isBlank()) {
                for (String arg : extraArgs.split(",")) {
                    if (!arg.isBlank()) command.add(arg.trim());
                }
            }
            command.add(url);
            ProcessResult r = run(command, parseTimeoutSec, null, null);
            if (r.exitCode != 0) {
                log.warn("resolve direct url failed (exit={}): {}", r.exitCode, lastLine(r.text));
                return null;
            }
            return r.text.lines().map(String::trim)
                    .filter(s -> s.startsWith("http"))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("resolve direct url error: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 下载 ====================

    /**
     * 下载单条视频到 tempDir，实时回调进度（0-100）。
     * formatKey：best | 1080p | 720p | 480p（空则用默认）。
     * 完成后返回最终文件。
     */
    public DownloadResult download(Long taskId, String url, Path tempDir,
                                   String formatKey, String referer,
                                   Consumer<Integer> progressCallback) throws Exception {
        Files.createDirectories(tempDir);
        List<String> command = new ArrayList<>(List.of(
                ytDlpPath,
                "--no-playlist",
                "--no-warnings",
                "--newline",
                "--no-part",
                "--no-mtime",
                "--retries", "3",
                "--fragment-retries", "3"));

        // 额外参数（如代理 --proxy,http://...），逐个加入，避免 shell 注入
        if (extraArgs != null && !extraArgs.isBlank()) {
            for (String arg : extraArgs.split(",")) {
                if (!arg.isBlank()) command.add(arg.trim());
            }
        }

        // 直链下载时带 Referer（防盗链站点必需，如 B站/百度 CDN）
        if (referer != null && !referer.isBlank()) {
            command.add("--referer");
            command.add(referer.trim());
        }

        command.addAll(List.of(
                "-f", resolveFormat(formatKey),
                "--merge-output-format", mergeFormat,
                "-o", tempDir.resolve("%(id)s_%(title).80s.%(ext)s").toString(),
                url));

        ProcessResult r = run(command, 1800, line -> {
            log.info("[Download-{}] {}", taskId, line);
            Matcher m = PROGRESS_PATTERN.matcher(line);
            if (m.find()) {
                double pct = Double.parseDouble(m.group(1));
                if (progressCallback != null) {
                    progressCallback.accept((int) Math.round(pct));
                }
            }
        }, taskId);

        if (r.exitCode != 0) {
            throw friendlyDownloadError(r.text);
        }

        // 定位最终文件：取最大的常规文件（避免合并临时文件）
        List<Path> files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    }).reversed())
                    .collect(Collectors.toList());
        }
        if (files.isEmpty()) {
            throw new PipelineException("下载完成但未找到输出文件: " + tempDir);
        }

        Path file = files.get(0);
        DownloadResult result = new DownloadResult();
        result.setFile(file);
        result.setExtension(getExtension(file.getFileName().toString()));
        try { result.setFileSize(Files.size(file)); } catch (IOException ignored) {}
        return result;
    }

    /** 将用户友好的清晰度标识映射为 yt-dlp 的 -f 选择器 */
    private String resolveFormat(String formatKey) {
        if (formatKey == null || formatKey.isBlank()) return format;
        return switch (formatKey) {
            case "1080p" -> "bv*[height<=1080]+ba/b[height<=1080]";
            case "720p" -> "bv*[height<=720]+ba/b[height<=720]";
            case "480p" -> "bv*[height<=480]+ba/b[height<=480]";
            case "direct" -> "b"; // 直链（mp4/m3u8 等）用单个最佳格式，避免 bv*+ba 对直链失败
            default -> format; // best 或未知，回退默认最高画质
        };
    }

    /** 取消运行中的下载（销毁进程） */
    public void cancel(Long taskId) {
        Process p = runningProcesses.remove(taskId);
        if (p != null) {
            p.destroyForcibly();
            log.info("Download task {} cancelled, process destroyed", taskId);
        }
    }

    // ==================== 内部工具 ====================

    private ProcessResult run(List<String> command, long timeoutSec,
                              Consumer<String> lineConsumer, Long cancelKey) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        log.info("yt-dlp command: {}", command);

        Process process = pb.start();
        if (cancelKey != null) runningProcesses.put(cancelKey, process);

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new PipelineException("命令执行超时(" + timeoutSec + "s)");
            }
            byte[] bytes = out.toByteArray();
            String text = decode(bytes);
            if (lineConsumer != null) {
                for (String line : text.split("\\r?\\n")) {
                    if (!line.isBlank()) lineConsumer.accept(line);
                }
            }
            return new ProcessResult(process.exitValue(), bytes, text);
        } finally {
            if (cancelKey != null) runningProcesses.remove(cancelKey);
        }
    }

    /** 优先按 UTF-8 严格解码，失败则回退 GBK（中文 Windows 下 yt-dlp 输出可能为 GBK） */
    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    private String lastLine(String text) {
        String[] lines = text.split("\\r?\\n");
        return lines.length > 0 ? lines[lines.length - 1].trim() : "";
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx + 1).toLowerCase() : "mp4";
    }

    // ==================== 数据结构 ====================

    @Data
    public static class ParseResult {
        private String type;
        private String pageTitle;
        private int count;
        private List<ParsedEntry> entries = new ArrayList<>();
    }

    @Data
    public static class ParsedEntry {
        private String id;
        private String title;
        private String url;
        private Double duration;
        private String uploader;
        private String thumbnail;
        private Long viewCount;
    }

    @Data
    public static class DownloadResult {
        private Path file;
        private String extension;
        private Long fileSize;
    }

    private record ProcessResult(int exitCode, byte[] bytes, String text) {}
}
