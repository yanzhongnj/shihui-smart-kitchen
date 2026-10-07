package com.videostep.locator.service.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.service.ai.VoiceIntentMapper;
import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 阿里云（百炼 / DashScope）语音识别引擎实现
 *
 * 模型：fun-asr-flash-2026-06-15 —— 这是【离线 / 一次性】识别模型（非实时流式），
 * 通过 HTTP 接口调用：
 *   POST {endpoint}（multimodal-generation/generation）
 *   Authorization: Bearer {ALIYUN_ASR_APP_KEY}
 *   body: {"model":"fun-asr-flash-2026-06-15",
 *          "input":{"messages":[{"role":"user","content":[{"audio":"data:audio/wav;base64,..."}]}]},
 *          "parameters":{"format":"wav","enable_itn":true}}
 *
 * 由于该模型不能像 FunASR 那样实时流式出字，本实现采用
 * 「后端 VAD 分句 + 逐句离线识别」方式，保持与 FunASR 一致的持续监听体验：
 * 前端持续上传 PCM s16le 16kHz（含静音），后端做能量检测切分出一句语音后，
 * 将整句打包为 WAV 发送给阿里云识别，识别完成回调命令结果，并继续监听下一句，
 * 直到语音会话结束。
 */
@Service
@Slf4j
public class AliyunAsrProvider implements VoiceRecognitionProvider, AiConfigRefreshable {

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BITS = 16;
    /** 单句缓冲上限（约 15 秒），防止持续说话时内存无限增长 */
    private static final int MAX_BUFFER_BYTES = SAMPLE_RATE * 2 * 15;

    private final VoiceIntentMapper voiceIntentMapper;
    private final Map<String, SessionHolder> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final ExecutorService executor;

    @Value("${voice-control.aliyun.app-key:}")
    private volatile String appKey;

    @Value("${voice-control.aliyun.model:fun-asr-flash-2026-06-15}")
    private volatile String model;

    @Value("${voice-control.aliyun.endpoint:https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation}")
    private volatile String endpoint;

    @Value("${voice-control.aliyun.vad.speech-threshold:0.008}")
    private volatile double speechThreshold;

    @Value("${voice-control.aliyun.vad.silence-ms:700}")
    private volatile long silenceMs;

    @Value("${voice-control.aliyun.vad.min-speech-ms:200}")
    private volatile long minSpeechMs;

    @Value("${voice-control.aliyun.vad.max-utterance-ms:10000}")
    private volatile long maxUtteranceMs;

    @Value("${voice-control.aliyun.http-timeout-ms:20000}")
    private volatile int httpTimeoutMs;

    public AliyunAsrProvider(VoiceIntentMapper voiceIntentMapper) {
        this.voiceIntentMapper = voiceIntentMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        AtomicInteger n = new AtomicInteger(1);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "aliyun-asr-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String providerKey() {
        return "asr.aliyun";
    }

    /** 配置中心热更新：新一句/新会话使用新配置（已开始会话沿用旧配置） */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        this.appKey = cfg.getApiKey();
        this.model = cfg.getModel();
        this.endpoint = cfg.getEndpoint();
        this.speechThreshold = cfg.getExtraDouble("speech_threshold", this.speechThreshold);
        this.silenceMs = cfg.getExtraLong("silence_ms", this.silenceMs);
        this.minSpeechMs = cfg.getExtraLong("min_speech_ms", this.minSpeechMs);
        this.maxUtteranceMs = cfg.getExtraLong("max_utterance_ms", this.maxUtteranceMs);
        this.httpTimeoutMs = cfg.getExtraInt("http_timeout_ms", this.httpTimeoutMs);
        log.info("AliyunAsrProvider 配置已热更新: model={}, hasKey={}",
                model, appKey != null && !appKey.isBlank());
    }

    @Override
    public void startSession(String sessionId, Consumer<AsrResult> callback) {
        sessions.put(sessionId, new SessionHolder(sessionId, callback, speechThreshold, false));
        log.info("Aliyun ASR session started (model={}): {}", model, sessionId);
    }

    @Override
    public void startTranscribeSession(String sessionId, Consumer<AsrResult> callback) {
        sessions.put(sessionId, new SessionHolder(sessionId, callback, speechThreshold, true));
        log.info("Aliyun ASR transcribe session started (model={}): {}", model, sessionId);
    }

    @Override
    public void sendAudio(String sessionId, byte[] audioData) {
        SessionHolder holder = sessions.get(sessionId);
        if (holder == null || holder.closed) return;
        double rms = rmsOf(audioData);
        long now = System.currentTimeMillis();
        boolean flush = false;
        synchronized (holder.lock) {
            if (rms >= holder.threshold) {
                // 说话中：追加到缓冲
                holder.pcm.write(audioData, 0, audioData.length);
                if (!holder.speaking) {
                    holder.speaking = true;
                    holder.speechStartMs = now;
                }
                holder.lastSpeechMs = now;
            } else if (holder.speaking) {
                // 尾音静音：继续保留少量静音，达到阈值则切句
                holder.pcm.write(audioData, 0, audioData.length);
                if (now - holder.lastSpeechMs >= silenceMs
                        || now - holder.speechStartMs >= maxUtteranceMs
                        || holder.pcm.size() >= MAX_BUFFER_BYTES) {
                    flush = true;
                }
            }
            // 已有识别请求在途时不重复切句，等其结束后由 recognize 的 finally 处理
            if (flush && !holder.recognizing) {
                flushLocked(holder);
            }
        }
    }

    @Override
    public void flush(String sessionId) {
        SessionHolder holder = sessions.get(sessionId);
        if (holder == null || holder.closed) return;
        synchronized (holder.lock) {
            if (holder.speaking && !holder.recognizing) {
                flushLocked(holder);
            }
        }
    }

    @Override
    public void endSession(String sessionId) {
        SessionHolder holder = sessions.remove(sessionId);
        if (holder == null) return;
        synchronized (holder.lock) {
            holder.closed = true;
            if (holder.speaking) {
                long dur = System.currentTimeMillis() - holder.speechStartMs;
                byte[] pcm = holder.pcm.toByteArray();
                if (dur >= minSpeechMs && pcm.length >= 3200) {
                    // 最后一句话没说完，也尽力识别一次
                    holder.pcm.reset();
                    holder.speaking = false;
                    holder.recognizing = true;
                    executor.execute(() -> recognize(holder, pcm));
                    return;
                }
            }
            holder.pcm.reset();
        }
        log.info("Aliyun ASR session ended: {}", sessionId);
    }

    @Override
    public boolean testConnection() {
        if (appKey == null || appKey.isBlank()) {
            log.warn("Aliyun ASR app-key not configured. Set ALIYUN_ASR_APP_KEY");
            return false;
        }
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                log.warn("Aliyun ASR endpoint invalid: {}", endpoint);
                return false;
            }
        } catch (Exception e) {
            log.warn("Aliyun ASR endpoint invalid: {}", endpoint);
            return false;
        }
        log.info("Aliyun ASR configured (model={}, endpoint={})", model, endpoint);
        return true;
    }

    @Override
    public String getName() {
        return "AliyunAsrProvider(" + model + ")";
    }

    /* ================== 内部实现 ================== */

    /** 持有 lock 时调用：取出缓冲音频并提交异步识别 */
    private void flushLocked(SessionHolder holder) {
        long dur = System.currentTimeMillis() - holder.speechStartMs;
        byte[] pcm = holder.pcm.toByteArray();
        holder.pcm.reset();
        holder.speaking = false;
        holder.speechStartMs = 0;
        holder.lastSpeechMs = 0;
        if (dur < minSpeechMs || pcm.length < 3200) {
            log.debug("Aliyun ASR discard short utterance: {}ms/{}B", dur, pcm.length);
            return;
        }
        holder.recognizing = true;
        executor.execute(() -> recognize(holder, pcm));
    }

    private void recognize(SessionHolder holder, byte[] pcm) {
        try {
            byte[] wav = toWav(pcm, SAMPLE_RATE, CHANNELS, BITS);
            HttpResponse<String> resp = postAudio(wav, httpTimeoutMs);
            if (resp.statusCode() != 200) {
                log.warn("Aliyun ASR recognize HTTP {}: {}", resp.statusCode(), sanitize(resp.body()));
                return;
            }
            String text = parseOutputText(resp.body());
            if (text != null && !text.isBlank()) {
                log.info("Aliyun ASR text[{}]: {}", holder.sessionId, text);
                if (holder.transcribe) {
                    // 纯转写模式：不匹配控制指令，直接回传识别文本
                    holder.callback.accept(AsrResult.builder()
                            .text(text).action("TRANSCRIBE").confidence(0.8f).build());
                } else {
                    AsrResult matched = voiceIntentMapper.match(text);
                    if (matched != null) {
                        holder.callback.accept(matched);
                    } else {
                        holder.callback.accept(AsrResult.builder()
                                .text(text).action("UNKNOWN").confidence(0.8f).build());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Aliyun ASR recognize failed: {}", holder.sessionId, e);
        } finally {
            // 识别期间可能又积累了新语音，结束后检查是否需要再次识别（保持持续监听）
            boolean flushAgain;
            long now = System.currentTimeMillis();
            synchronized (holder.lock) {
                holder.recognizing = false;
                flushAgain = holder.speaking && !holder.closed
                        && (now - holder.lastSpeechMs >= silenceMs
                            || now - holder.speechStartMs >= maxUtteranceMs
                            || holder.pcm.size() >= MAX_BUFFER_BYTES);
            }
            if (flushAgain) {
                synchronized (holder.lock) {
                    if (holder.speaking && !holder.recognizing && !holder.closed) {
                        flushLocked(holder);
                    }
                }
            }
        }
    }

    /** 调用阿里云 HTTP 接口识别一段 WAV */
    private HttpResponse<String> postAudio(byte[] wav, int timeoutMs) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(wav);
        String dataUrl = "data:audio/wav;base64," + b64;

        Map<String, Object> contentItem = new HashMap<>();
        contentItem.put("audio", dataUrl);
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(contentItem);
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", content);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg);
        Map<String, Object> input = new HashMap<>();
        input.put("messages", messages);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("format", "wav");
        parameters.put("enable_itn", true);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        String json = objectMapper.writeValueAsString(body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + appKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** 从阿里云响应中鲁棒地解析识别文本（兼容多种响应结构） */
    private String parseOutputText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode output = root.path("output");
        if (output.isMissingNode() || output.isNull()) return null;

        // 1) output.text（累计文本）
        String text = output.path("text").asText(null);
        if (text != null && !text.isBlank()) return text;

        // 2) output.choices[].message.content[].text（qwen 系列通用结构）
        JsonNode choices = output.path("choices");
        if (choices.isArray()) {
            for (JsonNode c : choices) {
                JsonNode msg = c.path("message");
                JsonNode content = msg.path("content");
                if (content.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode item : content) {
                        sb.append(item.path("text").asText(""));
                    }
                    String t = sb.toString();
                    if (!t.isBlank()) return t;
                } else if (content.isTextual() && !content.asText().isBlank()) {
                    return content.asText();
                }
            }
        }

        // 3) output.sentence.text（fun-asr-flash 文档结构）
        JsonNode sentence = output.path("sentence");
        if (sentence.isObject()) {
            String t = sentence.path("text").asText(null);
            if (t != null && !t.isBlank()) return t;
        }
        return null;
    }

    /** 简单能量（RMS，归一化到 0~1）检测 */
    private static double rmsOf(byte[] pcm) {
        int n = pcm.length / 2;
        if (n == 0) return 0;
        long sum = 0;
        for (int i = 0; i < pcm.length - 1; i += 2) {
            short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            long v = s;
            sum += v * v;
        }
        return Math.sqrt((double) sum / n) / 32768.0;
    }

    /** PCM s16le 打包为 WAV */
    private static byte[] toWav(byte[] pcm, int sampleRate, int channels, int bits) {
        int dataLen = pcm.length;
        int byteRate = sampleRate * channels * bits / 8;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(36 + dataLen);
        bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(byteRate);
        bb.putShort((short) (channels * bits / 8));
        bb.putShort((short) bits);
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(dataLen);
        bb.put(pcm);
        return bb.array();
    }

    /** 脱敏：避免把完整响应打到日志 */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private static final class SessionHolder {
        final String sessionId;
        final Consumer<AsrResult> callback;
        final Object lock = new Object();
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        final double threshold;
        final boolean transcribe; // true=纯转写模式（不匹配控制指令）
        volatile boolean closed = false;
        volatile boolean speaking = false;
        volatile boolean recognizing = false;
        volatile long speechStartMs = 0;
        volatile long lastSpeechMs = 0;

        SessionHolder(String sessionId, Consumer<AsrResult> callback, double threshold, boolean transcribe) {
            this.sessionId = sessionId;
            this.callback = callback;
            this.threshold = threshold;
            this.transcribe = transcribe;
        }
    }
}
