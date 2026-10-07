package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.StepResponse;
import com.videostep.locator.entity.Video;
import com.videostep.locator.exception.VideoNotFoundException;
import com.videostep.locator.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 游客体验视频：仅暴露 visibility=public 的视频及其步骤，供未登录用户在线体验。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExperienceService {

    private final VideoRepository videoRepository;
    private final StepService stepService;

    /** 游客体验视频列表（公开且已处理的视频） */
    public List<Map<String, Object>> list() {
        LambdaQueryWrapper<Video> qw = new LambdaQueryWrapper<Video>()
                .eq(Video::getVisibility, "public")
                .orderByDesc(Video::getCreatedAt);
        return videoRepository.selectList(qw).stream()
                .map(this::toListItem)
                .collect(Collectors.toList());
    }

    /** 体验视频详情（含步骤） */
    public Map<String, Object> detail(Long id) {
        Video v = videoRepository.selectById(id);
        if (v == null || !"public".equals(v.getVisibility())) {
            throw new VideoNotFoundException(id);
        }
        Map<String, Object> m = toListItem(v);
        m.put("steps", stepService.getSteps(id));
        return m;
    }

    private Map<String, Object> toListItem(Video v) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", v.getId());
        m.put("title", v.getTitle());
        m.put("duration", v.getDuration());
        m.put("stepCount", v.getStepCount());
        m.put("status", v.getStatus());
        return m;
    }
}
