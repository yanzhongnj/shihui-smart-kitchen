package com.videostep.locator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.dto.response.StepResponse;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.User;
import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoStep;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.ShareLinkRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.VideoShareRepository;
import com.videostep.locator.repository.VideoStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StepService {

    private final VideoStepRepository stepRepository;
    private final VideoRepository videoRepository;
    private final UserService userService;
    private final OrgAccessService orgAccessService;
    private final ShareLinkRepository shareLinkRepository;
    private final VideoShareRepository videoShareRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final ObjectMapper objectMapper;

    // 跟踪步骤循环状态: stepId -> isLooping
    private final Map<Long, Boolean> loopStatus = new ConcurrentHashMap<>();

    public List<StepResponse> getSteps(Long videoId) {
        List<VideoStep> steps = stepRepository.findByVideoIdOrderByStepIndexAsc(videoId);
        return steps.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /** 修改步骤文字：校验可管理 + 锁定（管理员豁免）；修改后审核回待审核并同步菜谱步骤 */
    @Transactional
    public StepResponse updateStep(Long stepId, Long userId, String summary, String originalText) {
        VideoStep step = stepRepository.selectById(stepId);
        if (step == null) throw new RuntimeException("步骤不存在: " + stepId);
        Video video = videoRepository.selectById(step.getVideoId());
        if (video == null) throw new RuntimeException("步骤所属视频不存在");
        boolean admin = isAdmin(userId);
        boolean owner = video.getUserId().equals(userId);
        boolean orgMember = video.getOrgId() != null && orgAccessService.isMember(userId, video.getOrgId());
        if (!admin && !owner && !orgMember) throw new SecurityException("无权修改此步骤");
        if (!admin && isLocked(video)) {
            throw new IllegalArgumentException("视频已发布或共享，步骤已锁定；请先取消发布/共享后再修改");
        }
        if (summary != null) step.setSummary(summary);
        if (originalText != null) step.setOriginalText(originalText);
        stepRepository.updateById(step);
        // 步骤文字变更 → 审核失效 + 同步菜谱步骤
        video.setReviewStatus(Video.REVIEW_PENDING);
        videoRepository.updateById(video);
        syncRecipeStep(video.getId(), step.getStepIndex(), step.getSummary());
        return toResponse(step);
    }

    private boolean isAdmin(Long userId) {
        User u = userService.getById(userId);
        return u != null && "ADMIN".equals(u.getRole());
    }

    private boolean isLocked(Video video) {
        if (Video.VISIBILITY_PUBLIC.equals(video.getVisibility())
                || Video.VISIBILITY_SHARED.equals(video.getVisibility())) return true;
        return shareLinkRepository.countActiveByVideoId(video.getId()) > 0
                || videoShareRepository.countActiveByVideoId(video.getId()) > 0;
    }

    /** 同步菜谱 steps_json 中与视频步骤同 index 的 summary（尽力而为，失败不阻断） */
    private void syncRecipeStep(Long videoId, Integer stepIndex, String summary) {
        try {
            RecipeMetadata recipe = recipeMetadataRepository.findByVideoId(videoId);
            if (recipe == null || recipe.getStepsJson() == null) return;
            List<Map<String, Object>> steps = objectMapper.readValue(recipe.getStepsJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            boolean changed = false;
            for (Map<String, Object> s : steps) {
                Object idx = s.get("index");
                if (idx != null && idx.toString().equals(String.valueOf(stepIndex))) {
                    s.put("summary", summary);
                    changed = true;
                    break;
                }
            }
            if (changed) {
                recipe.setStepsJson(objectMapper.writeValueAsString(steps));
                recipeMetadataRepository.updateById(recipe);
            }
        } catch (Exception e) {
            log.warn("sync recipe step failed video={}: {}", videoId, e.getMessage());
        }
    }

    public boolean toggleLoop(Long stepId) {
        boolean current = loopStatus.getOrDefault(stepId, false);
        boolean next = !current;
        loopStatus.put(stepId, next);
        return next;
    }

    public boolean getLoopStatus(Long stepId) {
        return loopStatus.getOrDefault(stepId, false);
    }

    private StepResponse toResponse(VideoStep step) {
        return StepResponse.builder()
                .id(step.getId())
                .stepIndex(step.getStepIndex())
                .summary(step.getSummary())
                .startTime(step.getStartTime())
                .endTime(step.getEndTime())
                .originalText(step.getOriginalText())
                .confidence(step.getConfidence())
                .isLooping(loopStatus.getOrDefault(step.getId(), false))
                .build();
    }
}
