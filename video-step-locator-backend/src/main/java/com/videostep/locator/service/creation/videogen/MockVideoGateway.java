package com.videostep.locator.service.creation.videogen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Mock 视频网关：用 FFmpeg lavfi 生成占位片段（纯色 + 静音，H.264+AAC），
 * P0/P2 开发/演示/免费体验用（engine=mock 不计费）。
 */
@Component
@Slf4j
public class MockVideoGateway implements AIVideoGenGateway {

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${creation.videogen.default-segment-sec:5}")
    private int defaultSegmentSec;

    /** Mock 每段模拟生成耗时（毫秒），让开发/演示时逐段进度可见（默认 800ms） */
    @Value("${creation.videogen.mock-delay-ms:800}")
    private long mockDelayMs;

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public boolean testConnection() {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            log.info("[MockVideoGateway] ffmpeg health={}", ok);
            return ok;
        } catch (Exception e) {
            log.warn("[MockVideoGateway] ffmpeg health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public VideoGenTaskRef submit(String prompt, int durationSec) {
        return submit(prompt, durationSec, null);
    }

    @Override
    public VideoGenTaskRef submit(String prompt, int durationSec, String label) {
        int dur = durationSec <= 0 ? defaultSegmentSec : durationSec;
        // 模拟逐段生成耗时（让前端进度条/分段状态可见）
        try {
            if (mockDelayMs > 0) Thread.sleep(mockDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 生成到临时目录，download() 时再拷到目标路径
        Path tmpDir;
        try {
            tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "ai_videogen");
            Files.createDirectories(tmpDir);
        } catch (Exception e) {
            throw new RuntimeException("创建临时目录失败: " + e.getMessage());
        }
        Path out = tmpDir.resolve("mock_" + UUID.randomUUID().toString().substring(0, 8) + ".mp4");
        try {
            runFfmpeg(generateCmd(out, dur, label));
            log.info("[MockVideoGateway] 生成占位片段 dur={}s label='{}' -> {}", dur, label, out);
            return new VideoGenTaskRef(provider(), out.toString());
        } catch (Exception e) {
            throw new RuntimeException("Mock 片段生成失败: " + e.getMessage());
        }
    }

    @Override
    public VideoGenResult poll(String taskRef) {
        // 报告片段真实时长（默认段长可能被按讲解时长覆盖，不能写死 defaultSegmentSec）
        double dur = probeDuration(taskRef);
        return new VideoGenResult("completed", taskRef, dur > 0 ? dur : defaultSegmentSec);
    }

    /** ffprobe 探测片段真实时长（秒），失败返回 0 */
    private double probeDuration(String file) {
        try {
            String probe = (ffmpegPath == null || ffmpegPath.isBlank())
                    ? "ffprobe" : ffmpegPath.replace("ffmpeg", "ffprobe");
            ProcessBuilder pb = new ProcessBuilder(probe, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null) return Double.parseDouble(line.trim());
            }
        } catch (Exception e) {
            log.warn("[MockVideoGateway] probe 失败 {}: {}", file, e.getMessage());
        }
        return 0;
    }

    @Override
    public String download(String resultUrl, String localPath) {
        try {
            Path src = Path.of(resultUrl);
            Path dst = Path.of(localPath);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return dst.toString();
        } catch (Exception e) {
            throw new RuntimeException("Mock 片段拉取失败: " + e.getMessage());
        }
    }

    private List<String> generateCmd(Path out, int dur, String label) {
        List<String> cmd = new java.util.ArrayList<>(List.of(ffmpegPath, "-y",
                "-f", "lavfi", "-i", String.format(Locale.ROOT,
                        "color=c=0x4F7CF7:s=1280x720:r=25:d=%d", dur),
                "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
                "-t", String.valueOf(dur)));
        String text = sanitizeText(label);
        if (!text.isEmpty()) {
            // drawtext 步骤文字（中文字体微软雅黑；过滤串内冒号/逗号等需转义）
            String filter = "drawtext=fontfile=C\\:/Windows/Fonts/msyh.ttc:text=" + text
                    + ":fontsize=40:fontcolor=white:box=1:boxcolor=black@0.45:boxborderw=14"
                    + ":x=(w-text_w)/2:y=h-text_h-42";
            cmd.add("-vf");
            cmd.add(filter);
        }
        cmd.addAll(List.of(
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest",
                "-movflags", "+faststart",
                out.toString()));
        return cmd;
    }

    /** 清理 drawtext 文本中 FFmpeg 过滤串特殊字符，限制长度 */
    private String sanitizeText(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\\\':%,;=\\[\\](){}<>|&*\"\\r\\n\\t]", " ");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > 24) t = t.substring(0, 24);
        return t;
    }

    private void runFfmpeg(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.debug("[MockVideoGateway] {}", line);
            }
        }
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new RuntimeException("ffmpeg 退出码 " + p.exitValue());
        }
    }
}
