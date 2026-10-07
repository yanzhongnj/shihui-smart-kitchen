package com.videostep.locator.service.asr;

import com.videostep.locator.dto.response.AsrResult;

import java.util.function.Consumer;

public interface VoiceRecognitionProvider {

    void startSession(String sessionId, Consumer<AsrResult> callback);

    /** 纯转写会话（不匹配控制指令，仅回传识别文本）——用于语音输入文字内容（创作/寻味按住说话）。 */
    default void startTranscribeSession(String sessionId, Consumer<AsrResult> callback) {
        startSession(sessionId, callback);
    }

    void sendAudio(String sessionId, byte[] audioData);

    void endSession(String sessionId);

    /**
     * 通知识别引擎「说话已结束」，促使其尽快产出最终结果（用于按住说话 PTT 的松开时机）。
     * 默认空实现；流式引擎（FunASR）通过向服务发送 is_speaking=false 触发最终结果。
     */
    default void flush(String sessionId) {
    }

    default boolean testConnection() {
        return true;
    }

    default String getName() {
        return this.getClass().getSimpleName();
    }
}
