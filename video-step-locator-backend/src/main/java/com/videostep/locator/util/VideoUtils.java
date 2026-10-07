package com.videostep.locator.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
public class VideoUtils {

    private static final Set<String> ALLOWED_FORMATS = Set.of("mp4", "webm", "avi", "mov", "mkv");

    private VideoUtils() {}

    public static String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    public static boolean isFormatAllowed(String filename) {
        return ALLOWED_FORMATS.contains(getExtension(filename));
    }

    public static boolean isFileSizeAllowed(Long fileSize) {
        return fileSize != null && fileSize <= 1073741824L; // 1GB
    }

    public static String generateVideoId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
