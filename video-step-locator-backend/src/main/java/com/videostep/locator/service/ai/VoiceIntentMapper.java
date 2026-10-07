package com.videostep.locator.service.ai;

import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.util.CommandMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语音命令语义映射（C 方案：混合）。
 *
 * 匹配策略：
 *  ① 规则（同义表）优先 —— 快、免费、稳定，绝大多数命令直接命中；
 *  ② 规则未命中且文本像命令（短句）→ LLM 语义分类兜底（语义相同即可映射，不局限于文字表面）；
 *  ③ LLM 命中 → 把该说法回填到规则表（下次同说法规则秒回，省去重复 LLM 调用）。
 *
 * 返回 null 表示无法识别（调用方按 UNKNOWN 处理）。
 */
@Service
@Slf4j
public class VoiceIntentMapper {

    /** 疑似短命令的最大长度（去标点后）：命令通常很短，长句（视频内容等）不浪费 LLM */
    private static final int MAX_COMMAND_LEN = 12;

    private final CommandMatcher ruleMatcher;
    private final VoiceLlmClassifier llmClassifier;

    public VoiceIntentMapper(CommandMatcher ruleMatcher, VoiceLlmClassifier llmClassifier) {
        this.ruleMatcher = ruleMatcher;
        this.llmClassifier = llmClassifier;
    }

    public AsrResult match(String text) {
        // ① 规则（同义表）优先
        AsrResult rule = ruleMatcher.match(text);
        if (rule != null) return rule;

        // 非疑似命令（长句）直接放弃，不浪费 LLM
        if (!isLikelyCommand(text)) return null;

        // ② LLM 语义分类兜底
        AsrResult llm = llmClassifier.classify(text);
        if (llm != null) {
            // ③ 学习回填：下次规则秒回
            ruleMatcher.learn(text, llm.getAction(), llm.getParams());
            log.info("Voice LLM fallback hit: text=[{}] action={} params={}",
                    text, llm.getAction(), llm.getParams());
            return llm;
        }
        return null;
    }

    private boolean isLikelyCommand(String text) {
        if (text == null) return false;
        String t = text.replaceAll("[\\s。，、！？；：,.!?;:'\"“”‘’「」·…\\-]+", "");
        return !t.isEmpty() && t.length() <= MAX_COMMAND_LEN;
    }
}
