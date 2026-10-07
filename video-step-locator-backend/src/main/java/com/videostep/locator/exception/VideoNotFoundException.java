package com.videostep.locator.exception;

public class VideoNotFoundException extends RuntimeException {
    public VideoNotFoundException(Long videoId) {
        super("视频不存在: " + videoId);
    }
}
