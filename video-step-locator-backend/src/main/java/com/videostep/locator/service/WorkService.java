package com.videostep.locator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.UserWork;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.UserWorkRepository;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 做菜作品（Keep 式作品记录）：发布（多图上传 + 关联菜谱）+ 我的作品集 + 删除 + 图片读取。
 * 发布作品 → EXP+20 → 徽章校验 → 寻味记忆信号（做过的菜）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkService {

    private static final int MAX_IMAGES = 9;
    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024L; // 5MB/张

    private final UserWorkRepository workRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final FileStorageService fileStorageService;
    private final GrowthService growthService;
    private final XunweiMemoryService xunweiMemoryService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> create(Long userId, Long videoId, String recipeTitle, String title,
                                      String description, String status, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少上传一张成品照片");
        }
        if (files.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("最多上传 " + MAX_IMAGES + " 张图片");
        }
        // 从关联菜谱带出菜名
        if (videoId != null) {
            RecipeMetadata recipe = recipeMetadataRepository.findByVideoId(videoId);
            if (recipe != null && (recipeTitle == null || recipeTitle.isBlank())) {
                recipeTitle = recipe.getTitle();
            }
        }
        if (recipeTitle == null || recipeTitle.isBlank()) recipeTitle = "家常菜";
        if (title == null || title.isBlank()) title = recipeTitle;
        if (status == null || status.isBlank()) status = "public";

        UserWork work = UserWork.builder()
                .userId(userId)
                .videoId(videoId)
                .recipeTitle(recipeTitle.trim())
                .title(title.trim())
                .description(description)
                .status(status)
                .build();
        workRepository.insert(work);

        // 保存图片（先落行拿 workId）
        List<String> paths = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile f = files.get(i);
                if (f.isEmpty()) continue;
                if (f.getSize() > MAX_IMAGE_BYTES) {
                    workRepository.physicalDeleteById(work.getId());
                    throw new IllegalArgumentException("单张图片不能超过 5MB");
                }
                paths.add(fileStorageService.saveWorkImage(
                        work.getId(), i, f.getInputStream(), f.getOriginalFilename()));
            }
        } catch (IOException e) {
            log.error("save work image failed, workId={}", work.getId(), e);
            workRepository.physicalDeleteById(work.getId());
            throw new IllegalArgumentException("图片保存失败，请重试");
        }
        if (paths.isEmpty()) {
            workRepository.physicalDeleteById(work.getId());
            throw new IllegalArgumentException("请至少上传一张有效的成品照片");
        }
        try {
            work.setImages(objectMapper.writeValueAsString(paths));
        } catch (Exception e) {
            log.error("serialize work images failed", e);
            work.setImages(String.join(",", paths));
        }
        workRepository.updateById(work);

        // 成长信号：作品 → EXP+20 → 徽章校验
        growthService.addExp(userId, 20, "work", work.getId());
        // 寻味记忆：做过的菜（正向信号）
        if (!"家常菜".equals(work.getRecipeTitle())) {
            xunweiMemoryService.ingestBehavior(userId, "work", work.getRecipeTitle(), true);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("workId", work.getId());
        result.put("newBadges", growthService.checkBadges(userId));
        return result;
    }

    public List<Map<String, Object>> listMine(Long userId) {
        return workRepository.findByUserId(userId).stream()
                .map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getById(Long userId, Long id) {
        UserWork work = workRepository.selectById(id);
        if (work == null) throw new IllegalArgumentException("作品不存在");
        if (!work.getUserId().equals(userId)) throw new IllegalArgumentException("无权查看该作品");
        return toMap(work);
    }

    public void delete(Long userId, Long id) {
        UserWork work = workRepository.selectById(id);
        if (work == null) throw new IllegalArgumentException("作品不存在");
        if (!work.getUserId().equals(userId)) throw new IllegalArgumentException("无权删除该作品");
        // 软删（保留磁盘图片以便回收站恢复；图片仅在「归档并彻底删除」时删）
        workRepository.softDeleteById(id);
    }

    /** 作品图片：属主校验后按索引返回 */
    public Resource getImage(Long userId, Long workId, int index) {
        UserWork work = workRepository.selectById(workId);
        if (work == null || !work.getUserId().equals(userId)) return null;
        List<String> images = parseImages(work.getImages());
        if (index < 0 || index >= images.size()) return null;
        return fileStorageService.loadWorkImage(images.get(index));
    }

    private Map<String, Object> toMap(UserWork w) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", w.getId());
        m.put("videoId", w.getVideoId());
        m.put("recipeTitle", w.getRecipeTitle());
        m.put("title", w.getTitle());
        m.put("description", w.getDescription());
        m.put("status", w.getStatus());
        m.put("createdAt", w.getCreatedAt());
        m.put("images", parseImages(w.getImages()));
        return m;
    }

    private List<String> parseImages(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<?> list = objectMapper.readValue(raw, List.class);
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        } catch (Exception e) {
            return Arrays.stream(raw.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
    }
}
