package com.videostep.locator.service.creation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.service.UsageService;
import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酿味 TTS 语音讲解（阿里云 qwen3-tts-instruct-flash）。
 *
 * <p>实测协议（2026-08-13 冒烟验证通过）：</p>
 * <pre>
 *   POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
 *   Headers: Authorization: Bearer &lt;key&gt; / Content-Type: application/json / X-DashScope-SSE: enable
 *   Body: { model:"qwen3-tts-instruct-flash",
 *           input:{ text:"要朗读的文本" },
 *           parameters:{ format:"wav", sample_rate:24000, streaming:false,
 *                        voice:"Cherry", instruction:"自然语言风格指令" } }
 *   SSE event:result → data:{ "output":{ "audio":{ "data":"<base64 分块>" }, "finish_reason":null|"stop" } }
 *     音频为裸 PCM s16le 分块（即便 format=wav 流式也不带头），最终分块带 output.audio.url（完整 WAV 的 OSS 签名 URL）
 * </pre>
 *
 * 本类：拼接所有 base64 分块 → 若含 RIFF 头直接使用，否则按 PCM s16le 包 WAV 头落盘。
 * 讲解文本与视频时间轴对齐：对全部步骤（含 timer 等待步骤）合成；视频生成时先逐段合成讲解，
 * 视频段时长跟随讲解时长（见 synthesizeNarration），保证语音与画面轨迹同步、不丢步骤。
 */
@Service
@Slf4j
public class CreationTtsService implements AiConfigRefreshable {

    private static final ObjectMapper OM = new ObjectMapper();

    private final CreationChatService chatService;
    private final UsageService usageService;
    private final AiCreationRepository creationRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    @Value("${creation.tts.api-key:}")
    private volatile String apiKey;

    @Value("${creation.tts.endpoint:https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation}")
    private volatile String endpoint;

    @Value("${creation.tts.model:qwen3-tts-instruct-flash}")
    private volatile String model;

    @Value("${creation.tts.voice:Cherry}")
    private volatile String voice;

    @Value("${creation.tts.format:wav}")
    private volatile String format;

    @Value("${creation.tts.sample-rate:24000}")
    private volatile int sampleRate;

    @Value("${creation.tts.instruction:你是一个温柔耐心的美食博主，用亲切自然的语气讲解做菜步骤，语速适中，吐字清晰。}")
    private volatile String defaultInstruction;

    @Value("${video.storage.base-path}")
    private String basePath;

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    public CreationTtsService(CreationChatService chatService,
                              UsageService usageService,
                              AiCreationRepository creationRepository) {
        this.chatService = chatService;
        this.usageService = usageService;
        this.creationRepository = creationRepository;
    }

    public boolean testConnection() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String providerKey() {
        return "tts.qwen3";
    }

    /** 配置中心热更新：下次请求即用新配置（改音色/模型/采样率等） */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        this.apiKey = cfg.getApiKey();
        this.endpoint = cfg.getEndpoint();
        this.model = cfg.getModel();
        this.voice = cfg.getExtra("voice", this.voice);
        this.format = cfg.getExtra("format", this.format);
        this.sampleRate = cfg.getExtraInt("sample_rate", this.sampleRate);
        this.defaultInstruction = cfg.getExtra("instruction", this.defaultInstruction);
        log.info("CreationTtsService 配置已热更新: model={}, voice={}, hasKey={}",
                model, voice, apiKey != null && !apiKey.isBlank());
    }

    /**
     * 合成一段语音，返回 WAV 字节（pcm_s16le 单声道，sampleRate）。
     */
    public byte[] synthesize(String text, String instruction) throws Exception {
        if (!testConnection()) {
            throw new IllegalStateException("语音讲解未配置（缺少 ALIYUN_ASR_APP_KEY）");
        }
        ObjectNode body = OM.createObjectNode();
        body.put("model", model);
        body.putObject("input").put("text", text);
        ObjectNode params = body.putObject("parameters");
        params.put("format", format);
        params.put("sample_rate", sampleRate);
        params.put("streaming", false);
        params.put("voice", voice);
        if (instruction != null && !instruction.isBlank()) {
            params.put("instruction", instruction);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-SSE", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(
                        OM.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = httpClient.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new RuntimeException("TTS 调用失败 HTTP " + res.statusCode() + ": " + res.body());
        }

        // 解析 SSE：累积 output.audio.data 的 base64 分块
        StringBuilder b64 = new StringBuilder();
        for (String line : res.body().split("\n")) {
            String t = line.trim();
            if (!t.startsWith("data:")) continue;
            String json = t.substring(5).trim();
            if (json.isEmpty()) continue;
            JsonNode node;
            try {
                node = OM.readTree(json);
            } catch (Exception e) {
                continue;
            }
            JsonNode audio = node.path("output").path("audio");
            String data = audio.path("data").asText("");
            if (!data.isEmpty()) b64.append(data);
            if ("stop".equals(node.path("output").path("finish_reason").asText(""))) {
                break;
            }
        }
        if (b64.length() == 0) {
            throw new RuntimeException("TTS 未返回音频数据: " + res.body());
        }
        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(b64.toString());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("TTS 音频 base64 解码失败", e);
        }
        // 流式返回为裸 PCM；若已含 RIFF 头则直接用，否则包 WAV 头
        if (audio.length >= 4 && audio[0] == 'R' && audio[1] == 'I'
                && audio[2] == 'F' && audio[3] == 'F') {
            return audio;
        }
        return wrapWav(audio);
    }

    /**
     * 为创作生成语音讲解（对视频步骤逐段合成，幂等：已存在文件跳过）。
     * 返回 { creationId, ready, needKey?, items:[{index, text, ready, durationSec, error?}] }
     */
    public Map<String, Object> generateNarrations(Long userId, Long creationId) {
        if (!testConnection()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("creationId", creationId);
            r.put("ready", false);
            r.put("needKey", true);
            r.put("message", "语音讲解未配置：请设置 ALIYUN_ASR_APP_KEY（DashScope key）");
            return r;
        }
        AiCreation creation = chatService.getOwnedCreation(userId, creationId);
        List<Map<String, Object>> steps = CreationJsonUtil.parseSteps(creation.getSteps());
        Path ttsDir = Path.of(basePath, "creations", String.valueOf(creationId), "tts");
        try {
            Files.createDirectories(ttsDir);
        } catch (Exception e) {
            log.warn("[酿味TTS] 创建目录失败: {}", e.getMessage());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            int index = ((Number) s.getOrDefault("index", 0)).intValue();
            String text = narrationText(index, s);
            Path file = ttsDir.resolve("step_" + index + ".wav");
            try {
                // 视频生成时通常已逐段合成讲解；此处兜底补缺（如直接调用生成接口）
                if (!existsNonEmpty(file)) {
                    byte[] audio = synthesize(text, defaultInstruction);
                    Files.write(file, audio);
                    recordTts(userId, creationId, text);
                    log.info("[酿味TTS] 生成讲解 step={} bytes={} text={}", index, audio.length, text);
                }
                items.add(statusItem(index, text, file, null));
            } catch (Exception e) {
                log.warn("[酿味TTS] 讲解生成失败 step={}: {}", index, e.getMessage());
                items.add(statusItem(index, text, file, e.getMessage()));
            }
        }
        boolean ready = !items.isEmpty() && items.stream()
                .allMatch(i -> Boolean.TRUE.equals(i.get("ready")));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("creationId", creationId);
        r.put("ready", ready);
        r.put("items", items);
        return r;
    }

    /** 语音讲解状态（不合成，仅报告已存在文件） */
    public Map<String, Object> narrationsStatus(Long userId, Long creationId) {
        AiCreation creation = chatService.getOwnedCreation(userId, creationId);
        List<Map<String, Object>> steps = CreationJsonUtil.parseSteps(creation.getSteps());
        Path ttsDir = Path.of(basePath, "creations", String.valueOf(creationId), "tts");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            int index = ((Number) s.getOrDefault("index", 0)).intValue();
            String text = narrationText(index, s);
            Path file = ttsDir.resolve("step_" + index + ".wav");
            if (existsNonEmpty(file)) {
                items.add(statusItem(index, text, file, null));
            }
        }
        boolean ready = !items.isEmpty() && items.stream()
                .allMatch(i -> Boolean.TRUE.equals(i.get("ready")));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("creationId", creationId);
        r.put("ready", ready);
        r.put("items", items);
        return r;
    }

    /** 返回讲解音频本地路径（播放鉴权由 controller 处理） */
    public Path resolveNarrationFile(Long userId, Long creationId, int stepIndex) {
        chatService.getOwnedCreation(userId, creationId);
        Path file = Path.of(basePath, "creations", String.valueOf(creationId),
                "tts", "step_" + stepIndex + ".wav");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("语音讲解尚未生成（step " + stepIndex + "）");
        }
        return file;
    }

    private Map<String, Object> statusItem(int index, String text, Path file, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("text", text);
        boolean ok = error == null && existsNonEmpty(file);
        m.put("ready", ok);
        if (ok) m.put("durationSec", probeDuration(file));
        if (error != null) m.put("error", error);
        return m;
    }

    /** 文件存在且非空（吞掉 IOException） */
    private boolean existsNonEmpty(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** ffprobe 探测音频时长（秒），失败返回 null */
    private Double probeDuration(Path file) {
        try {
            String cmd = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffprobe" : ffmpegPath.replace("ffmpeg", "ffprobe");
            ProcessBuilder pb = new ProcessBuilder(cmd, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null) {
                    double d = Double.parseDouble(line.trim());
                    return Math.round(d * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.debug("[酿味TTS] ffprobe 失败 {}: {}", file.getFileName(), e.getMessage());
        }
        return null;
    }

    /**
     * 视频生成时逐段调用：先合成该步骤语音讲解并落盘，返回其时长（秒）。
     * 返回 0 表示未配置 key 或合成失败（调用方按默认段长生成即可）。
     * 视频段时长由本方法返回值决定 → 语音与画面轨迹同步，不截断、不留大段空白。
     */
    public double synthesizeNarration(Long creationId, int stepIndex, Map<String, Object> step) {
        if (!testConnection()) return 0;
        String text = narrationText(stepIndex, step);
        Path ttsDir = Path.of(basePath, "creations", String.valueOf(creationId), "tts");
        try {
            Files.createDirectories(ttsDir);
        } catch (Exception e) {
            log.warn("[酿味TTS] 创建目录失败: {}", e.getMessage());
        }
        Path file = ttsDir.resolve("step_" + stepIndex + ".wav");
        try {
            byte[] audio = synthesize(text, defaultInstruction);
            Files.write(file, audio);
            Long ownerId = null;
            try {
                AiCreation c = creationRepository.selectById(creationId);
                ownerId = c == null ? null : c.getUserId();
            } catch (Exception ignore) { }
            recordTts(ownerId, creationId, text);
            log.info("[酿味TTS] 视频段生成时合成讲解 step={} bytes={} text={}", stepIndex, audio.length, text);
            Double d = probeDuration(file);
            return d == null ? 0 : d;
        } catch (Exception e) {
            log.warn("[酿味TTS] 讲解合成失败 step={}: {}", stepIndex, e.getMessage());
            return 0;
        }
    }

    /** TTS 用量记账（待办 #15）：actionType=tts，bytes=讲解字符数。仅记账，绝不干扰合成主流程。 */
    private void recordTts(Long userId, Long creationId, String text) {
        try {
            long chars = text == null ? 0 : text.length();
            if (chars <= 0) return;
            usageService.record(userId, null, "tts", creationId, chars);
        } catch (Exception e) {
            log.warn("[酿味TTS] 用量记账失败 creationId={}: {}", creationId, e.getMessage());
        }
    }

    /** 讲解文本：第N步，摘要。要点首句（≤25 字）。视频段时长会跟随讲解时长，保证语音与画面同步。 */
    private String narrationText(int index, Map<String, Object> s) {
        String summary = s.get("summary") == null ? "" : String.valueOf(s.get("summary"));
        String detail = s.get("detail") == null ? "" : String.valueOf(s.get("detail"));
        String first = firstSentence(detail, 25);
        StringBuilder sb = new StringBuilder();
        sb.append("第").append(index).append("步，").append(summary);
        if (!first.isEmpty()) sb.append("。").append(first);
        sb.append("。");
        return sb.toString();
    }

    private String firstSentence(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        String t = text.trim().replaceAll("[\\s\\r\\n]+", " ");
        int cut = -1;
        for (String p : new String[]{"。", "！", "？", "；", "."}) {
            int i = t.indexOf(p);
            if (i >= 0 && (cut < 0 || i < cut)) cut = i;
        }
        String sent = cut > 0 ? t.substring(0, cut + 1) : t;
        if (sent.length() > maxLen) sent = sent.substring(0, maxLen);
        return sent.trim();
    }

    /** 裸 PCM s16le 单声道 → 包 WAV 头 */
    private byte[] wrapWav(byte[] pcm) {
        int dataLen = pcm.length;
        int byteRate = sampleRate * 2;
        byte[] wav = new byte[44 + dataLen];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeInt(wav, 4, 36 + dataLen);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeInt(wav, 16, 16);
        writeShort(wav, 20, 1);      // 音频格式 PCM
        writeShort(wav, 22, 1);      // 单声道
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, byteRate);
        writeShort(wav, 32, 2);      // block align
        writeShort(wav, 34, 16);     // 位深
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeInt(wav, 40, dataLen);
        System.arraycopy(pcm, 0, wav, 44, dataLen);
        return wav;
    }

    private void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
