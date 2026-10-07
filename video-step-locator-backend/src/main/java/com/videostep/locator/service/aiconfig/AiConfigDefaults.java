package com.videostep.locator.service.aiconfig;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎配置元数据（v0.3 §0 真值表）：每个 provider 的可配置字段 + 环境变量/yml 回退路径。
 * 回退解析用 Spring {@code Environment.getProperty(ymlPath)} —— 与现有 {@code @Value}
 * 使用同一解析器，保证未初始化时解析结果与现状逐字节一致（零风险迁移）。
 */
@Component
public class AiConfigDefaults {

    /** 扩展字段定义 */
    public static class ExtraFieldDef {
        public final String key;
        public final String label;
        public final String ymlPath;
        public final String type; // string|int|long|boolean

        public ExtraFieldDef(String key, String label, String ymlPath, String type) {
            this.key = key;
            this.label = label;
            this.ymlPath = ymlPath;
            this.type = type;
        }
    }

    /** 引擎定义 */
    public static class ProviderDef {
        public final String type;
        public final String key;
        public final String displayName;
        public final String apiKeyYml;
        public final String endpointYml;
        public final String modelYml;
        public final String mockYml;
        public final List<ExtraFieldDef> extra;

        public ProviderDef(String type, String key, String displayName,
                           String apiKeyYml, String endpointYml, String modelYml,
                           String mockYml, List<ExtraFieldDef> extra) {
            this.type = type;
            this.key = key;
            this.displayName = displayName;
            this.apiKeyYml = apiKeyYml;
            this.endpointYml = endpointYml;
            this.modelYml = modelYml;
            this.mockYml = mockYml;
            this.extra = extra;
        }
    }

    private static final Map<String, ProviderDef> DEFS = new LinkedHashMap<>();

    static {
        List<ExtraFieldDef> e = new ArrayList<>();

        // LLM
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("timeout_ms", "超时(ms)", "xunwei.llm.timeout-ms", "long"));
        DEFS.put("llm.xunwei", new ProviderDef("llm", "llm.xunwei", "LLM 对话（DeepSeek）",
                "xunwei.llm.api-key", "xunwei.llm.base-url", "xunwei.llm.model", null, e));

        // 视觉
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("timeout_ms", "超时(ms)", "xunwei.vision.timeout-ms", "long"));
        DEFS.put("vision.qwen-vl", new ProviderDef("vision", "vision.qwen-vl", "视觉识别（Qwen-VL）",
                "xunwei.vision.api-key", "xunwei.vision.base-url", "xunwei.vision.model",
                "xunwei.vision.mock", e));

        // 视频 Wan
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("resolution", "分辨率", "creation.wan.resolution", "string"));
        e.add(new ExtraFieldDef("poll_interval_ms", "轮询间隔(ms)", "creation.videogen.poll-interval-ms", "long"));
        e.add(new ExtraFieldDef("default_segment_sec", "默认时长(s)", "creation.videogen.default-segment-sec", "int"));
        DEFS.put("video_gen.wan", new ProviderDef("video_gen", "video_gen.wan", "视频生成·Wan",
                "creation.wan.api-key", "creation.wan.endpoint", "creation.wan.model", null, e));

        // 视频 Seedance
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("query_endpoint", "查询端点", "creation.seedance.query-endpoint", "string"));
        e.add(new ExtraFieldDef("size", "尺寸", "creation.seedance.size", "string"));
        e.add(new ExtraFieldDef("poll_interval_ms", "轮询间隔(ms)", "creation.videogen.poll-interval-ms", "long"));
        e.add(new ExtraFieldDef("default_segment_sec", "默认时长(s)", "creation.videogen.default-segment-sec", "int"));
        DEFS.put("video_gen.seedance", new ProviderDef("video_gen", "video_gen.seedance", "视频生成·Seedance",
                "creation.seedance.api-key", "creation.seedance.endpoint", "creation.seedance.model", null, e));

        // 视频引擎路由
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("engine", "引擎", "creation.videogen.engine", "string"));
        DEFS.put("video_gen.engine", new ProviderDef("video_gen", "video_gen.engine", "视频生成·引擎路由",
                null, null, null, null, e));

        // 文生图 WanImage
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("size", "尺寸", "creation.effect-image.wan.size", "string"));
        e.add(new ExtraFieldDef("poll_interval_ms", "轮询间隔(ms)", "creation.effect-image.wan.poll-interval-ms", "long"));
        e.add(new ExtraFieldDef("timeout_ms", "超时(ms)", "creation.effect-image.wan.timeout-ms", "long"));
        DEFS.put("image_gen.wan_image", new ProviderDef("image_gen", "image_gen.wan_image", "文生图·WanImage",
                "creation.effect-image.wan.api-key", "creation.effect-image.wan.endpoint",
                "creation.effect-image.wan.model", null, e));

        // 文生图引擎路由
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("engine", "引擎", "creation.effect-image.provider", "string"));
        DEFS.put("image_gen.engine", new ProviderDef("image_gen", "image_gen.engine", "文生图·引擎路由",
                null, null, null, null, e));

        // 语音 Aliyun
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("speech_threshold", "语音阈值", "voice-control.aliyun.vad.speech-threshold", "double"));
        e.add(new ExtraFieldDef("silence_ms", "静音(ms)", "voice-control.aliyun.vad.silence-ms", "long"));
        e.add(new ExtraFieldDef("min_speech_ms", "最短语音(ms)", "voice-control.aliyun.vad.min-speech-ms", "long"));
        e.add(new ExtraFieldDef("max_utterance_ms", "最长句(ms)", "voice-control.aliyun.vad.max-utterance-ms", "long"));
        e.add(new ExtraFieldDef("http_timeout_ms", "HTTP超时(ms)", "voice-control.aliyun.http-timeout-ms", "int"));
        DEFS.put("asr.aliyun", new ProviderDef("asr", "asr.aliyun", "语音识别·阿里云",
                "voice-control.aliyun.app-key", "voice-control.aliyun.endpoint", null, null, e));

        // 语音 FunASR
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("host", "主机", "voice-control.funasr.host", "string"));
        e.add(new ExtraFieldDef("port", "端口", "voice-control.funasr.port", "int"));
        DEFS.put("asr.funasr", new ProviderDef("asr", "asr.funasr", "语音识别·FunASR",
                null, null, null, null, e));

        // 语音引擎路由
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("engine", "引擎", "voice-control.engine", "string"));
        DEFS.put("asr.engine", new ProviderDef("asr", "asr.engine", "语音识别·引擎路由",
                null, null, null, null, e));

        // TTS
        e = new ArrayList<>();
        e.add(new ExtraFieldDef("voice", "音色", "creation.tts.voice", "string"));
        e.add(new ExtraFieldDef("format", "格式", "creation.tts.format", "string"));
        e.add(new ExtraFieldDef("sample_rate", "采样率", "creation.tts.sample-rate", "int"));
        e.add(new ExtraFieldDef("instruction", "朗读指令", "creation.tts.instruction", "string"));
        DEFS.put("tts.qwen3", new ProviderDef("tts", "tts.qwen3", "语音讲解 TTS",
                "creation.tts.api-key", "creation.tts.endpoint", "creation.tts.model", null, e));
    }

    public static ProviderDef def(String providerKey) {
        return DEFS.get(providerKey);
    }

    public static List<ProviderDef> all() {
        return new ArrayList<>(DEFS.values());
    }

    public static Map<String, ProviderDef> map() {
        return DEFS;
    }
}
