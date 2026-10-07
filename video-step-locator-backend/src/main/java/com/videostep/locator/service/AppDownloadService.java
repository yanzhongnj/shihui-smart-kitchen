package com.videostep.locator.service;

import com.videostep.locator.entity.AppVersion;
import com.videostep.locator.repository.AppVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * APP 安装包（APK）存放与下载。
 *
 * <p><b>存放规范</b>：统一目录 {@code data/apk/}（可配 {@code app.apk-dir}），
 * 文件命名 {@code shihui-{platform}-{version}-{build}.apk}，
 * 例如 {@code shihui-android-1.1.0-10.apk}。管理端上传 APK 时按此规范落盘，
 * 并回填 {@code download_url} 与 {@code md5}，便于升级与统一下载。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppDownloadService {

    private final AppVersionRepository appVersionRepository;

    @Value("${app.apk-dir:data/apk}")
    private String apkDir;

    /** APK 规范目录（绝对路径，自动创建） */
    public Path dir() throws IOException {
        Path dir = Paths.get(apkDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir;
    }

    /** 按命名规范生成 APK 文件名 */
    public String fileName(AppVersion v) {
        String platform = v.getPlatform() == null ? "android" : v.getPlatform().toLowerCase();
        String version = v.getVersion() == null ? "0" : v.getVersion();
        int build = v.getBuild() == null ? 0 : v.getBuild();
        return "shihui-" + platform + "-" + version + "-" + build + ".apk";
    }

    /** 版本对应的 APK 文件（不存在返回 null） */
    public Path fileFor(AppVersion v) throws IOException {
        Path f = dir().resolve(fileName(v));
        return Files.isRegularFile(f) ? f : null;
    }

    /**
     * 保存上传的 APK：按规范命名落盘（覆盖旧文件），并计算 MD5。
     *
     * <p>为避免 Windows 下目标文件被临时占用导致 REPLACE_EXISTING 失败，
     * 采用「写临时文件 → 原子/普通替换」策略，并做一次短暂重试。</p>
     *
     * @return {path, size, md5}
     */
    public Map<String, Object> saveApk(AppVersion v, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("APK 文件不能为空");
        }
        String name = fileName(v);
        Path dir = dir();
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");

        // 写临时文件（全新创建，不触发目标替换）
        try (var in = file.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }

        // 替换目标：先尝试原子移动，失败则普通 move（带 REPLACE_EXISTING），再失败重试一次
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomicEx) {
                    // ATOMIC_MOVE 不支持/跨存储时抛异常 → 用普通 move（可覆盖）
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                last = null;
                break;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(300L * (attempt + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (last != null) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) { }
            throw last;
        }

        String md5 = md5(target);
        long size = Files.size(target);
        log.info("APK saved: {} ({} bytes, md5={})", target, size, md5);
        return Map.of(
                "path", target.toString(),
                "size", size,
                "md5", md5,
                "fileName", name);
    }

    /** 计算文件 MD5（十六进制小写） */
    public String md5(Path file) throws IOException {
        try (Stream<Path> ignored = Stream.empty()) {
            // no-op：保持 try-with-resources 语义清晰
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IOException("MD5 不可用", e);
        }
        byte[] bytes = Files.readAllBytes(file);
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    /** 目录下所有 APK 文件信息（供管理端浏览） */
    public List<Map<String, Object>> listApks() throws IOException {
        Path dir = dir();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".apk"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            out.add(Map.of(
                                    "fileName", p.getFileName().toString(),
                                    "size", Files.size(p),
                                    "lastModified", Files.getLastModifiedTime(p).toMillis()));
                        } catch (IOException e) {
                            log.warn("APK stat failed: {}", p, e);
                        }
                    });
        }
        return out;
    }

    /** 根据版本记录定位可下载文件，供 Controller 流式返回 */
    public Path resolveDownload(AppVersion v) throws IOException {
        return fileFor(v);
    }
}
