package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.RecyclePurgeJob;
import com.videostep.locator.service.RecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 全局回收站（仅 ADMIN 角色）。统一 /api/admin/recycle/**。
 *
 * <p>对软删除记录（cooking_checkins / diet_records / cooking_plans / user_works）提供
 * 恢复（restore）与「归档并彻底删除」（purge）。彻底删除前自动写入 *_archive 归档表（审计副本）。</p>
 *
 * <p>全部走原生 SQL（见 RecycleService 说明），不影响 APP 与用户端任何接口。</p>
 */
@RestController
@RequestMapping("/api/admin/recycle")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminRecycleController {

    private final RecycleService recycleService;
    private final RecyclePurgeJob recyclePurgeJob;

    // ---------------- 超期自动清理：配置 / 启停 / 手动触发 ----------------

    /** 读取自动清理配置（保留期/cron/启停/最近执行） */
    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> settings() {
        return ApiResponse.success(recyclePurgeJob.settings());
    }

    /** 更新配置（retentionDays / purgeCron / enabled 任一非空即更新，运行时生效） */
    @PutMapping("/settings")
    public ApiResponse<Map<String, Object>> updateSettings(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long operatorId) {
        Integer retention = body.get("retentionDays") instanceof Number n ? n.intValue() : null;
        String cron = body.get("purgeCron") instanceof String s ? s : null;
        Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
        if (retention == null && (cron == null || cron.isBlank()) && enabled == null) {
            return ApiResponse.error(400, "请至少提供一个要修改的配置项");
        }
        try {
            return ApiResponse.success(recyclePurgeJob.updateSettings(retention, cron, enabled, operatorId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 启用自动清理 */
    @PostMapping("/enable")
    public ApiResponse<Map<String, Object>> enable(@RequestAttribute("userId") Long operatorId) {
        return ApiResponse.success(recyclePurgeJob.updateSettings(null, null, true, operatorId));
    }

    /** 停用自动清理（手动触发不受影响） */
    @PostMapping("/disable")
    public ApiResponse<Map<String, Object>> disable(@RequestAttribute("userId") Long operatorId) {
        return ApiResponse.success(recyclePurgeJob.updateSettings(null, null, false, operatorId));
    }

    /** 手动触发一次清理（返回 {total, before, byType}） */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestAttribute("userId") Long operatorId) {
        try {
            return ApiResponse.success(recyclePurgeJob.purgeNow(operatorId));
        } catch (Exception e) {
            log.error("[recycle] 手动清理失败", e);
            return ApiResponse.error(500, "手动清理失败：" + e.getMessage());
        }
    }

    /** 已软删记录分页（type ∈ checkins/diet/plans/works；支持 userId/关键字筛选） */
    @GetMapping("/{type}")
    public ApiResponse<Map<String, Object>> list(
            @PathVariable String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.success(recycleService.listDeleted(type, userId, keyword, page, size));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 各类型已删数量 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> stats() {
        return ApiResponse.success(recycleService.stats());
    }

    /** 恢复（打卡级联恢复饮食） */
    @PostMapping("/{type}/{id}/restore")
    public ApiResponse<Map<String, Object>> restore(
            @PathVariable String type, @PathVariable Long id,
            @RequestAttribute("userId") Long operatorId) {
        try {
            recycleService.restore(type, id, operatorId);
            return ApiResponse.success(Map.of("restored", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    /** 归档并彻底删除（事务：归档表 → 物理删；作品同时删磁盘图） */
    @PostMapping("/{type}/{id}/purge")
    public ApiResponse<Map<String, Object>> purge(
            @PathVariable String type, @PathVariable Long id,
            @RequestAttribute("userId") Long operatorId) {
        try {
            recycleService.purge(type, id, operatorId);
            return ApiResponse.success(Map.of("purged", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    // ---------------- P2：批量操作（逐条调用 service，各自事务） ----------------

    /** 批量恢复（body: {"ids":[1,2,3]}） */
    @PostMapping("/{type}/batch-restore")
    public ApiResponse<Map<String, Object>> batchRestore(
            @PathVariable String type, @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long operatorId) {
        return batch(type, body, operatorId, true);
    }

    /** 批量归档并彻底删除（body: {"ids":[1,2,3]}） */
    @PostMapping("/{type}/batch-purge")
    public ApiResponse<Map<String, Object>> batchPurge(
            @PathVariable String type, @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long operatorId) {
        return batch(type, body, operatorId, false);
    }

    private ApiResponse<Map<String, Object>> batch(String type, Map<String, Object> body,
                                                   Long operatorId, boolean isRestore) {
        Object raw = body.get("ids");
        if (!(raw instanceof java.util.List<?> list) || list.isEmpty()) {
            return ApiResponse.error(400, "ids 不能为空");
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) ids.add(n.longValue());
        }
        if (ids.isEmpty()) return ApiResponse.error(400, "ids 必须为数字数组");
        java.util.List<Map<String, Object>> failed = new java.util.ArrayList<>();
        int success = 0;
        for (Long id : ids) {
            try {
                if (isRestore) recycleService.restore(type, id, operatorId);
                else recycleService.purge(type, id, operatorId);
                success++;
            } catch (IllegalArgumentException e) {
                failed.add(Map.of("id", id, "message", e.getMessage()));
            } catch (Exception e) {
                log.warn("[recycle] batch type={} id={} err={}", type, id, e.getMessage());
                failed.add(Map.of("id", id, "message", e.getMessage()));
            }
        }
        return ApiResponse.success(Map.of("success", success, "failed", failed));
    }

    // ---------------- P2：审计日志 ----------------

    /** 审计日志分页（type/action 可选筛选） */
    @GetMapping("/audit")
    public ApiResponse<Map<String, Object>> audit(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(recycleService.listAudit(type, action, page, size));
    }
}
