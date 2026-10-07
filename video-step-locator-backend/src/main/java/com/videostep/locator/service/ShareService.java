package com.videostep.locator.service;

import com.videostep.locator.dto.response.VideoResponse;
import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoShare;
import com.videostep.locator.exception.VideoNotFoundException;
import com.videostep.locator.repository.UserRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.VideoShareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShareService {

    private final VideoShareRepository shareRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final VideoService videoService;

    @Transactional
    public VideoShare shareVideo(Long videoId, Long sharedById, Long sharedWithId, String permission) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        if (!video.getUserId().equals(sharedById)) {
            throw new SecurityException("只有视频所有者才能分享");
        }
        if (!Video.REVIEW_APPROVED.equals(video.getReviewStatus())) {
            throw new IllegalArgumentException("视频尚未审核通过，不能分享");
        }
        VideoShare share = VideoShare.builder()
                .videoId(videoId)
                .sharedBy(sharedById)
                .sharedWith(sharedWithId)
                .permission(permission)
                .build();
        video.setVisibility(Video.VISIBILITY_SHARED);
        videoRepository.updateById(video);
        shareRepository.insert(share);
        return share;
    }

    @Transactional
    public void removeShare(Long videoId, Long sharedById, Long sharedWithId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        if (!video.getUserId().equals(sharedById)) {
            throw new SecurityException("只有视频所有者才能取消分享");
        }
        shareRepository.deleteByVideoIdAndSharedWithId(videoId, sharedWithId);
        videoService.refreshShareLock(videoId);
    }

    public List<VideoShare> getShares(Long videoId, Long userId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new VideoNotFoundException(videoId);
        if (!video.getUserId().equals(userId)) {
            throw new SecurityException("只有视频所有者才能查看分享列表");
        }
        return shareRepository.findByVideoId(videoId);
    }

    public List<VideoShare> getSharedWithMe(Long userId) {
        return shareRepository.findBySharedWithId(userId).stream()
                .filter(s -> !s.isExpired())
                .collect(Collectors.toList());
    }
}
