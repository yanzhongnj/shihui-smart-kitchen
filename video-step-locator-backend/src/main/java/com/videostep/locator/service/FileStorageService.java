package com.videostep.locator.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FileStorageService {

    @Value("${video.storage.base-path}")
    private String basePath;

    @Value("${video.storage.chunk-size}")
    private long chunkSize;

    private Path baseDir;

    @PostConstruct
    public void init() {
        baseDir = Path.of(basePath);
        try {
            Files.createDirectories(baseDir.resolve("videos"));
            Files.createDirectories(baseDir.resolve("temp"));
            Files.createDirectories(baseDir.resolve("audio"));
            Files.createDirectories(baseDir.resolve("segments"));
            Files.createDirectories(baseDir.resolve("steps"));
            Files.createDirectories(baseDir.resolve("works"));
            Files.createDirectories(baseDir.resolve("frames"));
            Files.createDirectories(baseDir.resolve("photos"));
            Files.createDirectories(baseDir.resolve("effect"));
            log.info("File storage initialized at: {}", baseDir);
        } catch (IOException e) {
            log.error("Failed to create storage directories", e);
        }
    }

    public Path getVideoPath(Long videoId, String filename) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return baseDir.resolve("videos").resolve(datePath)
                .resolve(videoId + "_" + filename);
    }

    public Path getChunkTempDir(String uploadId) {
        return baseDir.resolve("temp").resolve(uploadId);
    }

    public void saveChunk(String uploadId, int chunkIndex, InputStream data) throws IOException {
        Path tempDir = getChunkTempDir(uploadId);
        Files.createDirectories(tempDir);
        Path chunkFile = tempDir.resolve("chunk_" + chunkIndex);
        Files.copy(data, chunkFile, StandardCopyOption.REPLACE_EXISTING);
    }

    public List<Integer> getUploadedChunks(String uploadId) {
        Path tempDir = getChunkTempDir(uploadId);
        if (!Files.exists(tempDir)) return List.of();
        try {
            return Files.list(tempDir)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("chunk_"))
                    .map(n -> Integer.parseInt(n.substring(6)))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list chunks", e);
            return List.of();
        }
    }

    public void mergeChunks(String uploadId, Path targetPath, int totalChunks) throws IOException {
        Files.createDirectories(targetPath.getParent());
        try (var out = Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW)) {
            for (int i = 0; i < totalChunks; i++) {
                Path chunk = getChunkTempDir(uploadId).resolve("chunk_" + i);
                Files.copy(chunk, out);
            }
        }
        // 清理临时分片
        deleteDirectory(getChunkTempDir(uploadId));
        log.info("Merged {} chunks to {}", totalChunks, targetPath);
    }

    public Resource streamVideo(Path videoPath) {
        return new FileSystemResource(videoPath);
    }

    /**
     * 删除视频文件及其所有关联文件（steps / segments / transcripts / recipe / audio / logs）。
     * 命名约定：视频文件名主干 stem（去扩展名）作为所有关联文件的前缀，例如
     *   - 视频:   videos/yyyy/MM/dd/<videoId>_<title>.mp4   （上传后统一为 videoId 前缀）
     *   - 音频:   audio/<stem>.wav
     *   - 片段:   segments/<stem>_segments.json
     *   - 转录:   transcripts/<stem>_transcript.txt
     *   - 菜谱:   recipe/<stem>_recipe.json
     *   - 步骤:   steps/<stem>_steps.json
     * 因此按 stem 前缀即可一次性删除全部关联文件；同时兼容历史 <videoId>_ 前缀命名。
     */
    public void deleteVideoFiles(Long videoId, String filePath) {
        // 1) 精确删除视频文件本身
        if (filePath != null && !filePath.isBlank()) {
            Path p = Path.of(filePath);
            if (Files.exists(p)) {
                try {
                    Files.deleteIfExists(p);
                    log.info("Deleted video file: {}", p);
                } catch (IOException e) {
                    log.warn("Failed to delete video file: {}", p, e);
                }
            }
        }

        // 2) 由视频文件名计算 stem，删除所有以 stem 开头的关联文件
        String stem = null;
        if (filePath != null && !filePath.isBlank()) {
            String name = Path.of(filePath).getFileName().toString();
            int dot = name.lastIndexOf('.');
            stem = dot > 0 ? name.substring(0, dot) : name;
        }
        final String stemPrefix = (stem != null && !stem.isBlank()) ? stem + "_" : null;
        final String stemDot = (stem != null && !stem.isBlank()) ? stem + "." : null;
        final String idPrefix = videoId + "_";

        try {
            Files.walk(baseDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return (stemPrefix != null && (n.startsWith(stemPrefix) || n.startsWith(stemDot)))
                                || n.startsWith(idPrefix);
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            log.info("Deleted related file: {}", p);
                        } catch (IOException e) {
                            log.warn("Failed to delete related file: {}", p, e);
                        }
                    });

            // 3) 清理可能变空的目录（自底向上）
            try (var stream = Files.walk(baseDir)) {
                stream.filter(Files::isDirectory)
                        .sorted(Comparator.reverseOrder())
                        .forEach(dir -> {
                            if (dir.equals(baseDir)) return;
                            try (var children = Files.list(dir)) {
                                if (children.findAny().isEmpty()) {
                                    Files.deleteIfExists(dir);
                                }
                            } catch (IOException ignored) {
                                // 目录可能已被删除或占用，忽略
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Failed to delete related files for video {}", videoId, e);
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException e) { log.warn("Failed to delete: {}", p); }
                        });
            }
        }
    }

    // ---------- 作品图片（works） ----------

    /** 保存作品图片；返回相对路径（存 DB，如 works/2026/08/12/3_0_xxx.jpg） */
    public String saveWorkImage(Long workId, int index, InputStream data, String originalFilename) throws IOException {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        Path dir = baseDir.resolve("works").resolve(datePath);
        Files.createDirectories(dir);
        String safeName = sanitizeFileName(originalFilename);
        Path target = dir.resolve(workId + "_" + index + "_" + safeName);
        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        return "works/" + datePath + "/" + target.getFileName();
    }

    /** 按相对路径读取作品图片（防路径穿越） */
    public Resource loadWorkImage(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            log.warn("Blocked path traversal: {}", relativePath);
            return null;
        }
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) return null;
        return new FileSystemResource(resolved);
    }

    /** 删除某作品的所有图片（按 workId 前缀） */
    public void deleteWorkImages(Long workId) {
        String idPrefix = workId + "_";
        try {
            Path worksDir = baseDir.resolve("works");
            if (!Files.exists(worksDir)) return;
            try (var stream = Files.walk(worksDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith(idPrefix))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                                log.info("Deleted work image: {}", p);
                            } catch (IOException e) {
                                log.warn("Failed to delete work image: {}", p, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Failed to delete work images for {}", workId, e);
        }
    }

    // ---------- 拍照打卡照片（photos） ----------

    /** 保存拍照打卡照片；返回相对路径（存 DB，如 photos/2026/08/12/<userId>_<ts>_<seq>.jpg） */
    public String savePhotoImage(Long userId, InputStream data, String originalFilename) throws IOException {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        Path dir = baseDir.resolve("photos").resolve(datePath);
        Files.createDirectories(dir);
        String safeName = sanitizeFileName(originalFilename);
        String ts = String.valueOf(System.currentTimeMillis());
        Path target = dir.resolve(userId + "_" + ts + "_" + safeName);
        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        return "photos/" + datePath + "/" + target.getFileName();
    }

    /** 按相对路径读取照片（防路径穿越，复用 works 同款防护） */
    public Resource loadPhotoImage(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            log.warn("Blocked path traversal: {}", relativePath);
            return null;
        }
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) return null;
        return new FileSystemResource(resolved);
    }

    /** 删除单张照片（按相对路径） */
    public void deletePhotoImage(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return;
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            log.warn("Blocked path traversal on delete: {}", relativePath);
            return;
        }
        try {
            Files.deleteIfExists(resolved);
            log.info("Deleted photo: {}", resolved);
        } catch (IOException e) {
            log.warn("Failed to delete photo: {}", resolved, e);
        }
    }

    // ---------- 酱汁效果图（effect，AI 文生图） ----------

    /** 保存效果图；返回相对路径（存 DB，如 effect/2026/08/23/<creationId>_<ts>_<style>.png） */
    public String saveEffectImage(Long creationId, byte[] data, String style) throws IOException {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        Path dir = baseDir.resolve("effect").resolve(datePath);
        Files.createDirectories(dir);
        String st = style == null || style.isBlank() ? "realistic" : style;
        String ts = String.valueOf(System.currentTimeMillis());
        Path target = dir.resolve(creationId + "_" + ts + "_" + st + ".png");
        Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return "effect/" + datePath + "/" + target.getFileName();
    }

    /** 按相对路径读取效果图（防路径穿越，复用 works/photos 同款防护） */
    public Resource loadEffectImage(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            log.warn("Blocked path traversal: {}", relativePath);
            return null;
        }
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) return null;
        return new FileSystemResource(resolved);
    }

    /** 删除某创作的所有效果图（按 creationId 前缀，重生成时清理旧图） */
    public void deleteEffectImages(Long creationId) {
        String idPrefix = creationId + "_";
        try {
            Path effectDir = baseDir.resolve("effect");
            if (!Files.exists(effectDir)) return;
            try (var stream = Files.walk(effectDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith(idPrefix))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                                log.info("Deleted effect image: {}", p);
                            } catch (IOException e) {
                                log.warn("Failed to delete effect image: {}", p, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Failed to delete effect images for creation {}", creationId, e);
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "img.jpg";
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 60) safe = safe.substring(safe.length() - 60);
        return safe;
    }
}
