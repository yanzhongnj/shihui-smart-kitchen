package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.VideoResponse;
import com.videostep.locator.entity.Video;
import com.videostep.locator.exception.VideoNotFoundException;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.ShareLinkRepository;
import com.videostep.locator.repository.VideoShareRepository;
import com.videostep.locator.entity.User;
import com.videostep.locator.util.VideoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoService {

    private final VideoRepository videoRepository;
    private final FileStorageService fileStorageService;
    private final UserService userService;
    private final OrgAccessService orgAccessService;
    private final QuotaService quotaService;
    private final ShareLinkRepository shareLinkRepository;
    private final VideoShareRepository videoShareRepository;

    @Value("${video.storage.base-path}")
    private String basePath;

    /** 我的视频：分页 + 状态筛选 + 标题关键字 + 排序 */
    public Map<String, Object> getUserVideos(Long userId, int page, int size,
                                             String status, String keyword,
                                             String sortBy, String order) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        boolean asc = "asc".equalsIgnoreCase(order);
        LambdaQueryWrapper<Video> qw = new LambdaQueryWrapper<Video>()
                .eq(status != null && !status.isBlank(), Video::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), Video::getTitle, keyword);
        applyUserScope(qw, userId);
        if (sortBy != null && !sortBy.isBlank()) {
            switch (sortBy) {
                case "title" -> qw.orderBy(true, asc, Video::getTitle);
                case "fileSize" -> qw.orderBy(true, asc, Video::getFileSize);
                case "stepCount" -> qw.orderBy(true, asc, Video::getStepCount);
                case "updatedAt" -> qw.orderBy(true, asc, Video::getUpdatedAt);
                default -> qw.orderBy(true, asc, Video::getCreatedAt);
            }
        } else {
            qw.orderByDesc(Video::getCreatedAt);
        }
        long total = videoRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<VideoResponse> items = videoRepository.selectList(qw).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return Map.of("items", items, "total", total);
    }

    /** 平台管理：全量视频列表（跨用户，ADMIN 专用），返回 {items,total} */
    public Map<String, Object> adminGetVideos(int page, int size, String status,
                                              String keyword, String sortBy, String order) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        boolean asc = "asc".equalsIgnoreCase(order);
        LambdaQueryWrapper<Video> qw = new LambdaQueryWrapper<Video>()
                .eq(status != null && !status.isBlank(), Video::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), Video::getTitle, keyword);
        if (sortBy != null && !sortBy.isBlank()) {
            switch (sortBy) {
                case "title" -> qw.orderBy(true, asc, Video::getTitle);
                case "fileSize" -> qw.orderBy(true, asc, Video::getFileSize);
                case "stepCount" -> qw.orderBy(true, asc, Video::getStepCount);
                case "updatedAt" -> qw.orderBy(true, asc, Video::getUpdatedAt);
                default -> qw.orderBy(true, asc, Video::getCreatedAt);
            }
        } else {
            qw.orderByDesc(Video::getCreatedAt);
        }
        long total = videoRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<VideoResponse> items = videoRepository.selectList(qw).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return Map.of("items", items, "total", total);
    }

    public VideoResponse getVideoDetail(Long videoId, Long userId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        checkAccess(video, userId);
        return toResponse(video);
    }

    public Video getVideoEntity(Long videoId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        return video;
    }

    @Transactional
    public Video createVideo(Long userId, String deviceId, String title, String filename,
                              long fileSize, String md5, String filePath) {
        User user = userService.getById(userId);

        // B 端：企业成员创建的内容盖章 org_id，并在写操作前校验企业视频配额
        Long orgId = orgAccessService.effectiveOrgId(userId);
        if (orgId != null) quotaService.checkVideoQuota(orgId);

        Video video = Video.builder()
                .userId(user.getId())
                .orgId(orgId)
                .deviceId(deviceId)
                .title(title)
                .filename(filename)
                .filePath(filePath)
                .fileSize(fileSize)
                .format(VideoUtils.getExtension(filename))
                // 入库不自动处理：状态用 uploaded，由用户在列表/详情页手动触发流水线
                .status(Video.STATUS_UPLOADED)
                .visibility(Video.VISIBILITY_PRIVATE)
                .md5Hash(md5)
                .uploadProgress(100)
                .stepCount(0)
                .build();

        videoRepository.insert(video);
        return video;
    }

    public void deleteVideo(Long videoId, Long userId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        if (!video.getUserId().equals(userId)) {
            throw new SecurityException("无权删除此视频");
        }
        fileStorageService.deleteVideoFiles(videoId, video.getFilePath());
        videoRepository.deleteById(videoId);
    }

    public VideoResponse updateVideo(Long videoId, Long userId, String title) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        if (!canManage(video, userId) && !isAdmin(userId)) {
            throw new SecurityException("无权修改此视频");
        }
        if (!isAdmin(userId) && isLocked(video)) {
            throw new IllegalArgumentException("视频已发布或共享，内容已锁定；请先取消发布/共享后再修改");
        }
        if (title != null) video.setTitle(title);
        video.setReviewStatus(Video.REVIEW_PENDING); // 修改使审核失效，需重新审核
        videoRepository.updateById(video);
        return toResponse(video);
    }

    public String getVideoStreamPath(Long videoId, Long userId) {
        Video video = getVideoEntity(videoId);
        checkAccess(video, userId);
        return video.getFilePath();
    }

    /** 当前用户的视频统计（仪表盘）：总数 / 已完成 / 处理中 / 待处理(pending+uploaded+failed) */
    public Map<String, Object> getUserVideoStats(Long userId) {
        long total = countByStatus(userId, null);
        long completed = countByStatus(userId, Video.STATUS_COMPLETED);
        long processing = countByStatus(userId, Video.STATUS_PROCESSING);
        LambdaQueryWrapper<Video> pendingQw = new LambdaQueryWrapper<Video>()
                .in(Video::getStatus, Video.STATUS_PENDING, Video.STATUS_UPLOADED, Video.STATUS_FAILED);
        applyUserScope(pendingQw, userId);
        long pending = videoRepository.selectCount(pendingQw);
        return Map.of("total", total, "completed", completed, "processing", processing, "pending", pending);
    }

    /** 平台统计（ADMIN）：全量视频统计，与 adminGetVideos 口径一致 */
    public Map<String, Object> adminGetVideoStats() {
        long total = videoRepository.selectCount(null);
        long completed = videoRepository.selectCount(
                new LambdaQueryWrapper<Video>().eq(Video::getStatus, Video.STATUS_COMPLETED));
        long processing = videoRepository.selectCount(
                new LambdaQueryWrapper<Video>().eq(Video::getStatus, Video.STATUS_PROCESSING));
        long pending = videoRepository.selectCount(new LambdaQueryWrapper<Video>()
                .in(Video::getStatus, Video.STATUS_PENDING, Video.STATUS_UPLOADED, Video.STATUS_FAILED));
        return Map.of("total", total, "completed", completed, "processing", processing, "pending", pending);
    }

    private long countByStatus(Long userId, String status) {
        LambdaQueryWrapper<Video> qw = new LambdaQueryWrapper<>();
        applyUserScope(qw, userId);
        if (status != null) qw.eq(Video::getStatus, status);
        return videoRepository.selectCount(qw);
    }

    /** 数据隔离：企业成员看企业库（协作）；个人看自己且非企业内容 */
    private void applyUserScope(LambdaQueryWrapper<Video> qw, Long userId) {
        Long orgId = orgAccessService.effectiveOrgId(userId);
        if (orgId != null) {
            qw.eq(Video::getOrgId, orgId);
        } else {
            qw.eq(Video::getUserId, userId).isNull(Video::getOrgId);
        }
    }

    /** 审核：通过或驳回（写入 reviewStatus + reviewComment）。
     *  个人内容仅系统管理员可审；企业内容保留企业成员自审例外 + 系统管理员兜底。 */
    @Transactional
    public VideoResponse reviewResult(Long videoId, Long userId, boolean approved, String comment) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        if (!canReview(video, userId)) {
            throw new SecurityException("无权审核此视频");
        }
        video.setReviewStatus(approved ? Video.REVIEW_APPROVED : Video.REVIEW_REJECTED);
        video.setReviewComment(comment);
        videoRepository.updateById(video);
        return toResponse(video);
    }

    /** 发布到体验：须审核通过，置 visibility=public（B 端企业成员可发布企业内容到 C 端体验池） */
    @Transactional
    public VideoResponse publish(Long videoId, Long userId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        if (!canManage(video, userId)) {
            throw new SecurityException("无权操作此视频");
        }
        if (!Video.REVIEW_APPROVED.equals(video.getReviewStatus())) {
            throw new IllegalArgumentException("视频尚未审核通过，不能发布");
        }
        video.setVisibility(Video.VISIBILITY_PUBLIC);
        videoRepository.updateById(video);
        return toResponse(video);
    }

    /** 可管理：属主，或视频归属企业的在职成员（B 端企业协作） */
    private boolean canManage(Video video, Long userId) {
        if (video.getUserId().equals(userId)) return true;
        if (video.getOrgId() != null) return orgAccessService.isMember(userId, video.getOrgId());
        return false;
    }

    /** 系统管理员：User.role = ADMIN */
    public boolean isAdmin(Long userId) {
        User u = userService.getById(userId);
        return u != null && "ADMIN".equals(u.getRole());
    }

    /** 审核权：系统管理员全平台；企业内容（orgId!=null）保留企业成员自审例外；个人内容仅系统管理员 */
    private boolean canReview(Video video, Long userId) {
        if (isAdmin(userId)) return true;
        if (video.getOrgId() != null) return canManage(video, userId);
        return false;
    }

    /** 是否已发布/已共享（锁定）：public/shared，或存在未过期分享链接/定向分享 */
    public boolean isLocked(Video video) {
        if (Video.VISIBILITY_PUBLIC.equals(video.getVisibility())
                || Video.VISIBILITY_SHARED.equals(video.getVisibility())) {
            return true;
        }
        return shareLinkRepository.countActiveByVideoId(video.getId()) > 0
                || videoShareRepository.countActiveByVideoId(video.getId()) > 0;
    }

    /** 分享减少后评估锁定：无任何有效分享且未发布 → 回到 private + 审核回 pending（解锁） */
    @Transactional
    public void refreshShareLock(Long videoId) {
        Video v = videoRepository.selectById(videoId);
        if (v == null) return;
        if (Video.VISIBILITY_PUBLIC.equals(v.getVisibility())) return;
        boolean stillShared = shareLinkRepository.countActiveByVideoId(videoId) > 0
                || videoShareRepository.countActiveByVideoId(videoId) > 0;
        if (!stillShared) {
            v.setVisibility(Video.VISIBILITY_PRIVATE);
            v.setReviewStatus(Video.REVIEW_PENDING);
            videoRepository.updateById(v);
        }
    }

    /** 下架：取消公开（回到私密），审核回待审核（重新发布需重新审核）；系统管理员可强制 */
    @Transactional
    public VideoResponse unpublish(Long videoId, Long userId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        if (!video.getUserId().equals(userId) && !isAdmin(userId)) {
            throw new SecurityException("无权操作此视频");
        }
        video.setVisibility(Video.VISIBILITY_PRIVATE);
        video.setReviewStatus(Video.REVIEW_PENDING);
        videoRepository.updateById(video);
        return toResponse(video);
    }

    public Optional<Video> findByMd5(Long userId, String md5) {
        return videoRepository.findByMd5AndUserId(md5, userId);
    }

    public void checkAccess(Video video, Long userId) {
        if (video.getUserId().equals(userId)) return;
        if ("public".equals(video.getVisibility())) return;
        throw new SecurityException("无权访问此视频");
    }

    private VideoResponse toResponse(Video video) {
        return VideoResponse.builder()
                .id(video.getId())
                .userId(video.getUserId())
                .username(userService.getById(video.getUserId()).getUsername())
                .title(video.getTitle())
                .filename(video.getFilename())
                .fileSize(video.getFileSize())
                .duration(video.getDuration())
                .format(video.getFormat())
                .status(video.getStatus())
                .visibility(video.getVisibility())
                .reviewStatus(video.getReviewStatus())
                .reviewComment(video.getReviewComment())
                .uploadProgress(video.getUploadProgress())
                .stepCount(video.getStepCount())
                .createdAt(video.getCreatedAt())
                .updatedAt(video.getUpdatedAt())
                .build();
    }
}
