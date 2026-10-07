package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.AppVersion;
import com.videostep.locator.repository.AppVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppVersionService {

    private final AppVersionRepository appVersionRepository;
    private final AppDownloadService appDownloadService;

    /** 检查是否有新版本。current 为客户端当前版本号。 */
    public Map<String, Object> check(String platform, String current) {
        AppVersion latest = appVersionRepository.findLatestByPlatform(platform);
        Map<String, Object> m = new HashMap<>();
        if (latest == null) {
            m.put("update", false);
            m.put("latest", current);
            m.put("force", false);
            return m;
        }
        boolean hasNew = compareVersion(latest.getVersion(), current) > 0;
        boolean force = hasNew
                && Boolean.TRUE.equals(latest.getForceUpdate())
                && latest.getMinVersion() != null
                && compareVersion(current, latest.getMinVersion()) < 0;
        m.put("update", hasNew);
        m.put("latest", latest.getVersion());
        m.put("build", latest.getBuild());
        m.put("force", force);
        m.put("minVersion", latest.getMinVersion());
        m.put("changelog", latest.getChangelog());
        m.put("downloadUrl", latest.getDownloadUrl());
        m.put("md5", latest.getMd5());
        return m;
    }

    /** a>b → 1, a==b → 0, a<b → -1 */
    private int compareVersion(String a, String b) {
        String[] pa = (a == null ? "0" : a).split("\\.");
        String[] pb = (b == null ? "0" : b).split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseInt(pa[i]) : 0;
            int vb = i < pb.length ? parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    // ---------------- 平台管理（ADMIN） ----------------

    /** 版本列表：分页 + 平台筛选 + 关键字（版本号/更新说明） */
    public Map<String, Object> adminList(int page, int size, String platform, String keyword) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<AppVersion> qw = new LambdaQueryWrapper<AppVersion>()
                .eq(platform != null && !platform.isBlank(), AppVersion::getPlatform, platform);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(AppVersion::getVersion, keyword)
                    .or().like(AppVersion::getChangelog, keyword));
        }
        qw.orderByDesc(AppVersion::getPlatform).orderByDesc(AppVersion::getBuild);
        long total = appVersionRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<AppVersion> items = appVersionRepository.selectList(qw);
        return Map.of("items", items, "total", total);
    }

    /** 新增版本 */
    public AppVersion adminCreate(AppVersion v) {
        if (v.getPlatform() == null || v.getPlatform().isBlank()) throw new IllegalArgumentException("平台不能为空");
        if (v.getVersion() == null || v.getVersion().isBlank()) throw new IllegalArgumentException("版本号不能为空");
        v.setId(null);
        if (v.getEnabled() == null) v.setEnabled(true);
        if (v.getForceUpdate() == null) v.setForceUpdate(false);
        appVersionRepository.insert(v);
        return v;
    }

    /** 更新版本（部分字段更新） */
    public AppVersion adminUpdate(Long id, AppVersion v) {
        AppVersion existing = appVersionRepository.selectById(id);
        if (existing == null) throw new IllegalArgumentException("版本记录不存在");
        if (v.getPlatform() != null) existing.setPlatform(v.getPlatform());
        if (v.getVersion() != null) existing.setVersion(v.getVersion());
        if (v.getBuild() != null) existing.setBuild(v.getBuild());
        if (v.getMinVersion() != null) existing.setMinVersion(v.getMinVersion());
        if (v.getForceUpdate() != null) existing.setForceUpdate(v.getForceUpdate());
        if (v.getChangelog() != null) existing.setChangelog(v.getChangelog());
        if (v.getDownloadUrl() != null) existing.setDownloadUrl(v.getDownloadUrl());
        if (v.getMd5() != null) existing.setMd5(v.getMd5());
        if (v.getEnabled() != null) existing.setEnabled(v.getEnabled());
        appVersionRepository.updateById(existing);
        return existing;
    }

    /** 删除版本 */
    public void adminDelete(Long id) {
        appVersionRepository.deleteById(id);
    }

    /**
     * 上传 APK：按规范命名保存到 app.apk-dir，回填 download_url 与 md5。
     *
     * @return {path, size, md5, fileName, versionId, downloadUrl}
     */
    public Map<String, Object> uploadApk(Long id, MultipartFile file) throws IOException {
        AppVersion v = appVersionRepository.selectById(id);
        if (v == null) throw new IllegalArgumentException("版本记录不存在");
        Map<String, Object> saved = new java.util.LinkedHashMap<>(appDownloadService.saveApk(v, file));
        String platform = v.getPlatform() == null ? "android" : v.getPlatform();
        v.setDownloadUrl("/api/app/download?platform=" + platform + "&id=" + v.getId());
        v.setMd5((String) saved.get("md5"));
        appVersionRepository.updateById(v);
        saved.put("versionId", v.getId());
        saved.put("downloadUrl", v.getDownloadUrl());
        return saved;
    }
}