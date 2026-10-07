package com.videostep.locator.service.creation;

import java.util.List;
import java.util.Map;

/**
 * 酿味会话一次交互的结果。
 *
 * @param action       ask | confirm | revise_recipe | error
 * @param reply        给用户的回复文本
 * @param intentJson   assistant 消息落库的意图快照 JSON
 * @param creationId   生成/修订后的创作 id（可选）
 * @param version      修订后的版本号（可选）
 * @param safetyItems  规则库命中项（riskItems，可选；P1 附 safetyJson）
 */
public record CreationChatResult(
        String action,
        String reply,
        String intentJson,
        Long creationId,
        Integer version,
        List<Map<String, Object>> safetyItems) {
}
