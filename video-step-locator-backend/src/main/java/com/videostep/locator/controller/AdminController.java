package com.videostep.locator.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.AppVersion;
import com.videostep.locator.entity.DownloadTask;
import com.videostep.locator.entity.ProcessingTask;
import com.videostep.locator.entity.User;
import com.videostep.locator.repository.DownloadTaskRepository;
import com.videostep.locator.repository.ProcessingTaskRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.UserRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.service.AppVersionService;
import com.videostep.locator.service.OrgService;
import com.videostep.locator.service.UserService;
import com.videostep.locator.service.VideoService;
import com.videostep.locator.service.asr.FunasrAsrProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 平台管理接口（仅 ADMIN 角色）。统一 /api/admin/**。
 * 鉴权：类级 @PreAuthorize("hasRole('ADMIN')")，配合 SecurityConfig 的 @EnableMethodSecurity
 * 与 CustomUserDetailsService 的角色权限注入（ADMIN → ROLE_ADMIN）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserService userService;
    private final VideoService videoService;
    private final AppVersionService appVersionService;
    private final FunasrAsrProvider funasrAsrProvider;
    private final UserRepository userRepository;
    private final VideoRepository videoRepository;
    private final ProcessingTaskRepository taskRepository;
    private final DownloadTaskRepository downloadTaskRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final OrgService orgService;
    private final com.videostep.locator.service.BrandConfigService brandConfigService;
    private final com.videostep.locator.service.VendorService vendorService;

    @Value("${video.storage.base-path}")
    private String storageBasePath;

    // ---------------- 企业管理（B 端底座，P0） ----------------

    /** 企业列表（分页 + 关键字/状态/套餐筛选） */
    @GetMapping("/orgs")
    public ApiResponse<Map<String, Object>> orgs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String planType) {
        return ApiResponse.success(orgService.adminListOrgs(page, size, keyword, status, planType));
    }

    /** 企业详情统计 + 用量 */
    @GetMapping("/orgs/{orgId}/stats")
    public ApiResponse<Map<String, Object>> orgStats(@PathVariable Long orgId) {
        return ApiResponse.success(orgService.adminOrgStats(orgId));
    }

    /** 企业状态变更（0禁用 1正常 2欠费停服） */
    @PutMapping("/orgs/{orgId}/status")
    public ApiResponse<Object> orgStatus(@PathVariable Long orgId, @RequestBody Map<String, Object> body) {
        Integer status = body.get("status") == null ? null : Integer.parseInt(String.valueOf(body.get("status")));
        return ApiResponse.success(orgService.adminSetStatus(orgId, status));
    }

    /** 企业套餐变更 */
    @PutMapping("/orgs/{orgId}/plan")
    public ApiResponse<Object> orgPlan(@PathVariable Long orgId, @RequestBody Map<String, String> body) {
        return ApiResponse.success(orgService.adminSetPlan(orgId, body.get("planCode")));
    }

    /** A1：Admin 维护企业品牌配置（白标） */
    @PutMapping("/orgs/{orgId}/brand")
    public ApiResponse<Object> orgBrand(@PathVariable Long orgId, @RequestBody Map<String, String> body) {
        return ApiResponse.success(brandConfigService.upsert(orgId, body));
    }

    // ---------------- A2：厂商管理（Admin） ----------------

    /** 厂商总览（所有企业 + 各厂商业务计数），供管理端表格式列表 */
    @GetMapping("/vendors")
    public ApiResponse<Object> vendorSummary() {
        return ApiResponse.success(vendorService.listVendorSummary());
    }

    /** 厂商合同列表（可按企业筛选） */
    @GetMapping("/vendors/contracts")
    public ApiResponse<Object> vendorContracts(@RequestParam(required = false) Long orgId) {
        return ApiResponse.success(vendorService.listContracts(orgId));
    }

    /** 新建厂商合同 */
    @PostMapping("/vendors/contracts")
    public ApiResponse<Object> createContract(@RequestBody Map<String, String> body) {
        Long orgId = Long.valueOf(body.get("orgId"));
        Integer quota = body.get("deviceQuota") == null ? null : Integer.valueOf(body.get("deviceQuota"));
        return ApiResponse.success(vendorService.createContract(orgId, body.get("contractNo"), quota, body.get("status")));
    }

    /** 删除厂商合同 */
    @DeleteMapping("/vendors/contracts/{id}")
    public ApiResponse<Object> deleteContract(@PathVariable Long id) {
        return ApiResponse.success(Map.of("deleted", vendorService.deleteContract(id)));
    }

    /** 删除 SKU（连同其绑定码） */
    @DeleteMapping("/vendors/skus/{id}")
    public ApiResponse<Object> deleteSku(@PathVariable Long id) {
        return ApiResponse.success(Map.of("deleted", vendorService.deleteSku(id)));
    }

    /** 删除内容授权 */
    @DeleteMapping("/vendors/licenses/{id}")
    public ApiResponse<Object> deleteLicense(@PathVariable Long id) {
        return ApiResponse.success(Map.of("deleted", vendorService.deleteLicense(id)));
    }

    /** 删除绑定码（仅未激活） */
    @DeleteMapping("/vendors/bindings/{id}")
    public ApiResponse<Object> deleteBinding(@PathVariable Long id) {
        return ApiResponse.success(Map.of("deleted", vendorService.deleteBinding(id)));
    }

    /** 合同状态变更 */
    @PutMapping("/vendors/contracts/{id}/status")
    public ApiResponse<Object> contractStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.success(vendorService.setContractStatus(id, body.get("status")));
    }

    /** SKU 列表 */
    @GetMapping("/vendors/skus")
    public ApiResponse<Object> vendorSkus(@RequestParam(required = false) Long orgId) {
        return ApiResponse.success(vendorService.listSkus(orgId));
    }

    /** 新建 SKU */
    @PostMapping("/vendors/skus")
    public ApiResponse<Object> createSku(@RequestBody Map<String, String> body) {
        Long orgId = Long.valueOf(body.get("orgId"));
        return ApiResponse.success(vendorService.createSku(orgId, body.get("sku"), body.get("deviceType"),
                body.get("capabilities"), body.get("defaultGroup")));
    }

    /** 为 SKU 批量生成绑定码 */
    @PostMapping("/vendors/skus/{id}/bind-codes")
    public ApiResponse<Object> generateBindCodes(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        int count = body.get("count") == null ? 10 : Integer.parseInt(String.valueOf(body.get("count")));
        return ApiResponse.success(vendorService.generateBindCodes(id, count));
    }

    /** 绑定码列表（激活统计） */
    @GetMapping("/vendors/bindings")
    public ApiResponse<Map<String, Object>> vendorBindings(
            @RequestParam(required = false) Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(vendorService.listBindings(orgId, page, size));
    }

    /** 激活统计 */
    @GetMapping("/vendors/activation-stats")
    public ApiResponse<Object> vendorActivationStats(@RequestParam(required = false) Long orgId) {
        return ApiResponse.success(vendorService.activationStats(orgId));
    }

    /** 内容授权列表 */
    @GetMapping("/vendors/licenses")
    public ApiResponse<Object> vendorLicenses(@RequestParam(required = false) Long orgId) {
        return ApiResponse.success(vendorService.listLicenses(orgId));
    }

    /** 新增内容授权 */
    @PostMapping("/vendors/licenses")
    public ApiResponse<Object> addLicense(@RequestBody Map<String, String> body) {
        Long orgId = Long.valueOf(body.get("orgId"));
        return ApiResponse.success(vendorService.addLicense(orgId, body.get("recipeGroup")));
    }

    /** 内容授权上下架 */
    @PutMapping("/vendors/licenses/{id}/status")
    public ApiResponse<Object> licenseStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.success(vendorService.setLicenseStatus(id, body.get("status")));
    }

    /** 可用套餐列表 */
    @GetMapping("/org-plans")
    public ApiResponse<java.util.List<Map<String, Object>>> orgPlans() {
        return ApiResponse.success(orgService.listPlans());
    }

    // ---------------- B3：账单管理（Admin） ----------------

    /** 账单列表（可按企业筛选） */
    @GetMapping("/invoices")
    public ApiResponse<Map<String, Object>> invoices(
            @RequestParam(required = false) Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(orgService.adminListInvoices(orgId, page, size));
    }

    /** 手动开票 */
    @PostMapping("/orgs/{orgId}/invoices")
    public ApiResponse<Object> createInvoice(@RequestAttribute("userId") Long userId,
                                             @PathVariable Long orgId,
                                             @RequestBody Map<String, String> body) {
        return ApiResponse.success(orgService.adminCreateInvoice(orgId, userId, body.get("amount"), body.get("description")));
    }

    /** 账单状态流转 */
    @PutMapping("/invoices/{invoiceId}/status")
    public ApiResponse<Object> invoiceStatus(@PathVariable Long invoiceId,
                                             @RequestBody Map<String, String> body) {
        return ApiResponse.success(orgService.adminSetInvoiceStatus(invoiceId, body.get("status")));
    }

    // ---------------- 用户管理 ----------------

    /** 用户列表（分页 + 关键字 + 状态/角色筛选 + 排序） */
    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String order) {
        return ApiResponse.success(userService.adminListUsers(
                page, size, keyword, status, role, sortBy, order));
    }

    /** 启用/禁用用户 */
    @PutMapping("/users/{id}/status")
    public ApiResponse<Map<String, Object>> setUserStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        User u = userService.adminSetStatus(id,
                body.get("status") instanceof Number n ? n.intValue() : null);
        return ApiResponse.success(Map.of("id", u.getId(), "status", u.getStatus()));
    }

    /** 设置角色（ADMIN/USER） */
    @PutMapping("/users/{id}/role")
    public ApiResponse<Map<String, Object>> setUserRole(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        User u = userService.adminSetRole(id,
                body.get("role") instanceof String s ? s : null);
        return ApiResponse.success(Map.of("id", u.getId(), "role", u.getRole()));
    }

    /** 新增用户（ADMIN 专用） */
    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        User u = userService.adminCreateUser(
                str(body.get("username")), str(body.get("email")),
                str(body.get("password")), str(body.get("nickname")),
                str(body.get("role")), intOrNull(body.get("status")));
        return ApiResponse.success(Map.of("id", u.getId(), "username", u.getUsername()));
    }

    /** 修改用户（昵称/邮箱/密码/角色/状态） */
    @PutMapping("/users/{id}")
    public ApiResponse<Map<String, Object>> updateUser(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        User u = userService.adminUpdateUser(id,
                str(body.get("email")), str(body.get("password")),
                str(body.get("nickname")), str(body.get("role")),
                intOrNull(body.get("status")));
        return ApiResponse.success(Map.of("id", u.getId(), "username", u.getUsername()));
    }

    // ---------------- 全局视频 ----------------

    /** 全量视频（跨用户，分页 + 状态/关键字筛选 + 排序），条目含 username */
    @GetMapping("/videos")
    public ApiResponse<Map<String, Object>> videos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String order) {
        return ApiResponse.success(videoService.adminGetVideos(
                page, size, status, keyword, sortBy, order));
    }

    // ---------------- 全局下载任务 ----------------

    /** 全量下载任务（跨用户/跨设备，分页 + 筛选 + 排序） */
    @GetMapping("/downloads")
    public ApiResponse<Map<String, Object>> downloads(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String order) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        boolean asc = "asc".equalsIgnoreCase(order);
        LambdaQueryWrapper<DownloadTask> qw = new LambdaQueryWrapper<DownloadTask>()
                .eq(status != null && !status.isBlank(), DownloadTask::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), DownloadTask::getTitle, keyword);
        if (sortBy != null && !sortBy.isBlank()) {
            switch (sortBy) {
                case "title" -> qw.orderBy(true, asc, DownloadTask::getTitle);
                case "fileSize" -> qw.orderBy(true, asc, DownloadTask::getFileSize);
                case "status" -> qw.orderBy(true, asc, DownloadTask::getStatus);
                default -> qw.orderBy(true, asc, DownloadTask::getCreatedAt);
            }
        } else {
            qw.orderByDesc(DownloadTask::getCreatedAt);
        }
        long total = downloadTaskRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = downloadTaskRepository.selectList(qw).stream()
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", t.getId());
                    m.put("userId", t.getUserId());
                    m.put("username", username(t.getUserId()));
                    m.put("deviceId", t.getDeviceId());
                    m.put("title", t.getTitle());
                    m.put("uploader", t.getUploader());
                    m.put("status", t.getStatus());
                    m.put("progress", t.getProgress());
                    m.put("format", t.getFormat());
                    m.put("fileSize", t.getFileSize());
                    m.put("errorMessage", t.getErrorMessage());
                    m.put("createdAt", t.getCreatedAt());
                    return m;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    // ---------------- APP 版本发布管理 ----------------

    /** 版本列表（分页 + 平台筛选 + 关键字） */
    @GetMapping("/app-versions")
    public ApiResponse<Map<String, Object>> appVersions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(appVersionService.adminList(page, size, platform, keyword));
    }

    /** 新增版本 */
    @PostMapping("/app-versions")
    public ApiResponse<AppVersion> createAppVersion(@RequestBody AppVersion body) {
        try {
            return ApiResponse.success(appVersionService.adminCreate(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 更新版本 */
    @PutMapping("/app-versions/{id}")
    public ApiResponse<AppVersion> updateAppVersion(@PathVariable Long id, @RequestBody AppVersion body) {
        try {
            return ApiResponse.success(appVersionService.adminUpdate(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 删除版本 */
    @DeleteMapping("/app-versions/{id}")
    public ApiResponse<Map<String, Object>> deleteAppVersion(@PathVariable Long id) {
        appVersionService.adminDelete(id);
        return ApiResponse.success(Map.of("deleted", true));
    }

    /**
     * 上传 APK 安装包：按规范命名（shihui-{platform}-{version}-{build}.apk）
     * 保存到 app.apk-dir 目录，并回填 download_url 与 md5，便于扫码下载与升级。
     */
    @PostMapping(value = "/app-versions/{id}/apk", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> uploadAppApk(
            @PathVariable Long id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            Map<String, Object> saved = appVersionService.uploadApk(id, file);
            return ApiResponse.success("上传成功", saved);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("APK upload failed id={}", id, e);
            return ApiResponse.error(500, "上传失败: " + e.getMessage());
        }
    }

    // ---------------- 平台统计 ----------------

    /** 平台级统计：用户/视频/任务/下载/菜谱 + 存储占用 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        long totalUsers = userRepository.selectCount(null);
        long totalVideos = videoRepository.selectCount(null);
        long totalTasks = taskRepository.selectCount(null);
        long queuedTasks = taskRepository.countByStatus(ProcessingTask.STATUS_QUEUED);
        long runningTasks = taskRepository.countByStatus(ProcessingTask.STATUS_RUNNING);
        long totalDownloads = downloadTaskRepository.selectCount(null);
        long totalRecipes = recipeMetadataRepository.selectCount(null);

        Map<String, Object> data = new HashMap<>();
        data.put("totalUsers", totalUsers);
        data.put("totalVideos", totalVideos);
        data.put("totalTasks", totalTasks);
        data.put("queuedTasks", queuedTasks);
        data.put("runningTasks", runningTasks);
        data.put("totalDownloads", totalDownloads);
        data.put("totalRecipes", totalRecipes);
        data.put("storage", storageInfo());
        return ApiResponse.success(data);
    }

    // ---------------- 系统健康 ----------------

    /** 系统健康：数据库 / 存储目录 / 活跃任务 */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();

        try {
            userRepository.selectCount(null);
            data.put("database", "UP");
        } catch (Exception e) {
            data.put("database", "DOWN: " + e.getMessage());
        }

        try {
            Path base = Path.of(storageBasePath);
            data.put("storage", Map.of(
                    "path", base.toAbsolutePath().toString(),
                    "exists", Files.exists(base),
                    "writable", Files.isWritable(base),
                    "freeBytes", base.toFile().getUsableSpace()));
        } catch (Exception e) {
            data.put("storage", Map.of("error", e.getMessage()));
        }

        // FunASR 健康：仅 TCP 端口探测（testConnection 无副作用，不改动语音识别会话/流程）
        try {
            data.put("funasr", funasrAsrProvider.testConnection() ? "UP" : "DOWN");
        } catch (Exception e) {
            data.put("funasr", "DOWN: " + e.getMessage());
        }

        long active = taskRepository.countByStatus(ProcessingTask.STATUS_QUEUED)
                + taskRepository.countByStatus(ProcessingTask.STATUS_RUNNING);
        data.put("activeTasks", active);
        data.put("serverTime", java.time.LocalDateTime.now().toString());
        data.put("status", "OK");
        return ApiResponse.success(data);
    }

    private String username(Long userId) {
        if (userId == null) return null;
        try { return userService.getById(userId).getUsername(); }
        catch (Exception e) { return null; }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** 存储目录占用信息 */
    private Map<String, Object> storageInfo() {
        Map<String, Object> m = new HashMap<>();
        try {
            Path base = Path.of(storageBasePath);
            m.put("path", base.toAbsolutePath().toString());
            m.put("exists", Files.exists(base));
            m.put("writable", Files.isWritable(base));
            long free = base.toFile().getUsableSpace();
            m.put("freeBytes", free);
            m.put("freeGb", free > 0 ? Math.round(free / 1024.0 / 1024.0 / 1024.0 * 10.0) / 10.0 : 0);
            Path videosDir = base.resolve("videos");
            if (Files.isDirectory(videosDir)) {
                try (var s = Files.walk(videosDir)) {
                    long used = s.filter(Files::isRegularFile).mapToLong(p -> {
                        try { return Files.size(p); } catch (Exception e) { return 0L; }
                    }).sum();
                    m.put("usedBytes", used);
                    m.put("usedGb", Math.round(used / 1024.0 / 1024.0 / 1024.0 * 10.0) / 10.0);
                }
            }
        } catch (Exception e) {
            m.put("error", e.getMessage());
        }
        return m;
    }
}
