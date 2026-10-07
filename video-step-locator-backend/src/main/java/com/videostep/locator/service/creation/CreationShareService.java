package com.videostep.locator.service.creation;

import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiCreationShare;
import com.videostep.locator.entity.User;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.repository.AiCreationShareRepository;
import com.videostep.locator.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 酿味创作站外只读分享（P4）：生成分享码 + 匿名解析创作详情（标题/用料/步骤/免责声明）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationShareService {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CreationChatService chatService;
    private final AiCreationShareRepository shareRepository;
    private final AiCreationRepository creationRepository;
    private final UserRepository userRepository;

    public Map<String, Object> generate(Long userId, Long creationId, Integer expireDays) {
        chatService.getOwnedCreation(userId, creationId);
        AiCreationShare s = AiCreationShare.builder()
                .code(generateCode())
                .creationId(creationId)
                .createdBy(userId)
                .expiresAt(expireDays != null && expireDays > 0
                        ? LocalDateTime.now().plusDays(expireDays) : null)
                .build();
        shareRepository.insert(s);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", s.getCode());
        r.put("shareUrl", "/s/creation/" + s.getCode());
        return r;
    }

    /** 匿名只读解析分享（站外） */
    public Map<String, Object> resolve(String code) {
        AiCreationShare s = shareRepository.findByCode(code);
        if (s == null) throw new IllegalArgumentException("分享链接不存在");
        if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("分享链接已过期");
        }
        AiCreation c = creationRepository.selectById(s.getCreationId());
        if (c == null) throw new IllegalArgumentException("创作不存在或已删除");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("creationId", c.getId());
        m.put("title", c.getTitle());
        m.put("summary", c.getSummary());
        m.put("mainIngredients", CreationJsonUtil.parseStringArray(c.getMainIngredients()));
        m.put("auxiliaryIngredients", CreationJsonUtil.parseStringArray(c.getAuxiliaryIngredients()));
        m.put("tasteTags", CreationJsonUtil.parseStringArray(c.getTasteTags()));
        m.put("allergens", CreationJsonUtil.parseStringArray(c.getAllergens()));
        m.put("spiceLevel", c.getSpiceLevel());
        m.put("calories", c.getCalories());
        m.put("estimatedTime", c.getEstimatedTime());
        m.put("difficulty", c.getDifficulty());
        m.put("steps", CreationJsonUtil.parseSteps(c.getSteps()));
        m.put("disclaimer", "AI 创作菜谱仅供参考");
        User owner = userRepository.selectById(s.getCreatedBy());
        m.put("shareBy", owner != null ? owner.getUsername() : null);
        return m;
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
