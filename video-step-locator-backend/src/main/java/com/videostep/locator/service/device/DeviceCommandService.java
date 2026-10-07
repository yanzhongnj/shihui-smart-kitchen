package com.videostep.locator.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.DeviceCommandLog;
import com.videostep.locator.entity.KitchenDevice;
import com.videostep.locator.entity.RecipeDeviceParam;
import com.videostep.locator.repository.DeviceCommandLogRepository;
import com.videostep.locator.repository.KitchenDeviceRepository;
import com.videostep.locator.repository.RecipeDeviceParamRepository;
import com.videostep.locator.service.OrgAccessService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厨电指令编排（A3 P0）：绑定/解绑 → 归一化指令 → 异步下发 → 落日志 → 事件入库 + WS 推送。
 * 网关路由/降级走 DeviceGatewayRegistry（首选不可达 → mock）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandService {

    private final KitchenDeviceRepository deviceRepository;
    private final RecipeDeviceParamRepository paramRepository;
    private final DeviceCommandLogRepository logRepository;
    private final DeviceEventService eventService;
    private final DeviceGatewayRegistry gatewayRegistry;
    private final OrgAccessService orgAccessService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 指令下发线程池（自建，避免容器注入歧义；与 ThreadPoolConfig 的 deviceExecutor 同思路） */
    private final java.util.concurrent.ExecutorService deviceExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "device-worker");
                t.setDaemon(true);
                return t;
            });

    @PostConstruct
    public void registerEventSinks() {
        gatewayRegistry.getGateways().forEach(g -> g.setEventSink(this::handleGatewayEvent));
    }

    // ---------------- 设备绑定 ----------------

    @Transactional
    public KitchenDevice bind(Long userId, String vendor, String nickname, String deviceType) {
        DeviceGateway gateway = gatewayRegistry.getGateway(vendor);
        DeviceCommandResult r = gateway.bind(userId, nickname, deviceType);
        if (!r.success || r.result == null) {
            throw new IllegalArgumentException(r.message != null ? r.message : "绑定失败");
        }
        KitchenDevice dev = KitchenDevice.builder()
                .userId(userId)
                .orgId(orgAccessService.effectiveOrgId(userId))
                .vendor(vendor)
                .vendorDeviceId(r.result)
                .nickname(nickname)
                .deviceType(deviceType == null || deviceType.isBlank() ? "oven" : deviceType)
                .online(1)
                .build();
        deviceRepository.insert(dev);
        return dev;
    }

    @Transactional
    public void unbind(Long userId, Long deviceId) {
        KitchenDevice dev = requireOwnedDevice(userId, deviceId);
        try {
            gatewayRegistry.getGateway(dev.getVendor()).unbind(dev.getVendorDeviceId());
        } catch (Exception e) {
            log.warn("unbind vendor side failed device={}: {}", deviceId, e.getMessage());
        }
        deviceRepository.deleteById(deviceId);
    }

    /** 我的设备列表（企业成员看企业设备，个人看自己的） */
    public Map<String, Object> listDevices(Long userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        Long orgId = orgAccessService.effectiveOrgId(userId);
        LambdaQueryWrapper<KitchenDevice> qw = new LambdaQueryWrapper<>();
        if (orgId != null) {
            qw.eq(KitchenDevice::getOrgId, orgId);
        } else {
            qw.eq(KitchenDevice::getUserId, userId).isNull(KitchenDevice::getOrgId);
        }
        qw.orderByDesc(KitchenDevice::getId);
        long total = deviceRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        return Map.of("items", deviceRepository.selectList(qw), "total", total);
    }

    // ---------------- 菜谱参数一键下发（P4 增量） ----------------

    /** 菜谱设备参数 → 按类型自动找已绑定在线设备下发（无匹配设备给友好提示） */
    public Map<String, Object> sendRecipeParam(Long userId, Long recipeId, Long paramId) {
        RecipeDeviceParam p = paramRepository.selectById(paramId);
        if (p == null || !recipeId.equals(p.getRecipeId())) {
            throw new IllegalArgumentException("菜谱设备参数不存在");
        }
        KitchenDevice dev = deviceRepository.findOnlineByType(
                orgAccessService.effectiveOrgId(userId), userId, p.getDeviceType());
        if (dev == null) {
            throw new IllegalArgumentException("未绑定可用的" + deviceTypeName(p.getDeviceType()) + "设备");
        }
        return sendCommand(userId, dev.getId(), paramId, null);
    }

    private String deviceTypeName(String t) {
        return switch (t == null ? "" : t) {
            case "oven" -> "烤箱";
            case "steamer" -> "蒸箱";
            case "cooktop" -> "灶具";
            case "airfryer" -> "空气炸锅";
            default -> t == null ? "厨电" : t;
        };
    }

    // ---------------- 指令下发 ----------------
    // 注意：此处不能加 @Transactional —— 异步线程执行时需立即读到刚插入的日志行（跨连接读不到未提交数据）

    public Map<String, Object> sendCommand(Long userId, Long deviceId, Long paramId, Map<String, Object> commandMap) {
        KitchenDevice dev = requireOwnedDevice(userId, deviceId);
        DeviceCommand cmd;
        Long refParamId = paramId;
        if (paramId != null) {
            RecipeDeviceParam p = paramRepository.selectById(paramId);
            if (p == null) throw new IllegalArgumentException("菜谱设备参数不存在");
            cmd = new DeviceCommand(resolveAction(p.getMode()), p.getMode(), p.getTemperature(), p.getDurationMin());
        } else {
            cmd = new DeviceCommand();
            if (commandMap == null || commandMap.get("action") == null) {
                throw new IllegalArgumentException("指令动作(action)不能为空");
            }
            cmd.action = String.valueOf(commandMap.get("action"));
            cmd.mode = commandMap.get("mode") == null ? null : String.valueOf(commandMap.get("mode"));
            cmd.temperature = intOf(commandMap.get("temperature"));
            cmd.durationMin = intOf(commandMap.get("durationMin"));
        }

        DeviceCommandLog logRow = DeviceCommandLog.builder()
                .deviceId(deviceId)
                .paramId(refParamId)
                .command(serialize(cmd))
                .status(DeviceCommandLog.STATUS_QUEUED)
                .build();
        logRepository.insert(logRow);

        // 异步下发
        deviceExecutor.execute(() -> execute(dev, cmd, logRow.getId()));
        return Map.of("logId", logRow.getId(), "deviceId", deviceId, "command", cmd.describe(), "message", "指令已下发");
    }

    private void execute(KitchenDevice dev, DeviceCommand cmd, Long logId) {
        DeviceCommandLog row = logRepository.selectById(logId);
        if (row == null) return;
        row.setStatus(DeviceCommandLog.STATUS_SENT);
        logRepository.updateById(row);
        try {
            DeviceGateway gateway = gatewayRegistry.getGatewayWithFallback(dev.getVendor());
            DeviceCommandResult r = gateway.sendCommand(dev.getVendorDeviceId(), cmd);
            row.setStatus(r.success ? DeviceCommandLog.STATUS_SUCCESS : DeviceCommandLog.STATUS_FAILED);
            row.setResult(r.result);
            if (!r.success) row.setErrorMessage(r.message);
        } catch (Exception e) {
            row.setStatus(DeviceCommandLog.STATUS_FAILED);
            row.setErrorMessage(e.getMessage());
        }
        row.setCompletedAt(LocalDateTime.now());
        logRepository.updateById(row);
    }

    /** 失败指令重试 */
    public Map<String, Object> retry(Long userId, Long logId) {
        DeviceCommandLog row = logRepository.selectById(logId);
        if (row == null) throw new IllegalArgumentException("指令日志不存在");
        KitchenDevice dev = requireOwnedDevice(userId, row.getDeviceId());
        if (!DeviceCommandLog.STATUS_FAILED.equals(row.getStatus())) {
            throw new IllegalArgumentException("只有失败指令可重试");
        }
        DeviceCommand cmd = deserialize(row.getCommand());
        row.setStatus(DeviceCommandLog.STATUS_QUEUED);
        row.setErrorMessage(null);
        row.setResult(null);
        row.setCompletedAt(null);
        logRepository.updateById(row);
        deviceExecutor.execute(() -> execute(dev, cmd, row.getId()));
        return Map.of("logId", logId, "message", "指令已重新下发");
    }

    /** 我的指令日志 */
    public Map<String, Object> listCommandLogs(Long userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        Long orgId = orgAccessService.effectiveOrgId(userId);
        // 我的设备 id 集合
        List<KitchenDevice> devices;
        if (orgId != null) {
            devices = deviceRepository.selectList(new LambdaQueryWrapper<KitchenDevice>().eq(KitchenDevice::getOrgId, orgId));
        } else {
            devices = deviceRepository.selectList(new LambdaQueryWrapper<KitchenDevice>()
                    .eq(KitchenDevice::getUserId, userId).isNull(KitchenDevice::getOrgId));
        }
        if (devices.isEmpty()) return Map.of("items", List.of(), "total", 0L);
        List<Long> deviceIds = devices.stream().map(KitchenDevice::getId).toList();
        LambdaQueryWrapper<DeviceCommandLog> qw = new LambdaQueryWrapper<DeviceCommandLog>()
                .in(DeviceCommandLog::getDeviceId, deviceIds)
                .orderByDesc(DeviceCommandLog::getId);
        long total = logRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DeviceCommandLog l : logRepository.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("deviceId", l.getDeviceId());
            m.put("paramId", l.getParamId());
            m.put("command", deserialize(l.getCommand()));
            m.put("status", l.getStatus());
            m.put("result", l.getResult());
            m.put("errorMessage", l.getErrorMessage());
            m.put("createdAt", l.getCreatedAt());
            m.put("completedAt", l.getCompletedAt());
            items.add(m);
        }
        return Map.of("items", items, "total", total);
    }

    /** 菜谱设备参数（公开只读） */
    public List<RecipeDeviceParam> getRecipeDeviceParams(Long recipeId) {
        return paramRepository.selectList(new LambdaQueryWrapper<RecipeDeviceParam>()
                .eq(RecipeDeviceParam::getRecipeId, recipeId)
                .orderByAsc(RecipeDeviceParam::getStepIndex));
    }

    /** P1：从流水线 LLM 输出（rawLlmJson）解析 device_params 入库（先清后插，幂等） */
    @Transactional
    public void saveDeviceParamsFromRecipe(Long recipeId, Map<String, Object> data) {
        try {
            Object dp = data.get("device_params");
            if (!(dp instanceof List<?> list)) return;
            if (list.isEmpty()) return;
            paramRepository.delete(new LambdaQueryWrapper<RecipeDeviceParam>()
                    .eq(RecipeDeviceParam::getRecipeId, recipeId));
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> pm)) continue;
                RecipeDeviceParam p = RecipeDeviceParam.builder()
                        .recipeId(recipeId)
                        .stepIndex(intOf(pm.get("step_index")))
                        .deviceType(strOf(pm.get("device_type")))
                        .mode(strOf(pm.get("mode")))
                        .temperature(intOf(pm.get("temperature")))
                        .durationMin(intOf(pm.get("duration_min")))
                        .note(strOf(pm.get("note")))
                        .build();
                paramRepository.insert(p);
            }
            log.info("菜谱 {} 设备参数已保存 {} 条", recipeId, list.size());
        } catch (Exception e) {
            log.warn("保存菜谱设备参数失败 recipe={}: {}", recipeId, e.getMessage());
        }
    }

    private String strOf(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * P1 补充：非视频来源（图文录入/手动录入/AI 创作镜像）步骤含 温度/模式 时，
     * 按步骤派生厨电参数并入库（先清后插，幂等），使「厨电联动」对这些菜谱同样可用。
     */
    @Transactional
    public void deriveDeviceParamsFromSteps(Long recipeId, List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) return;
        List<Map<String, Object>> params = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> st = steps.get(i);
            Integer temp = intOf(st.get("temperature"));
            String mode = strOf(st.get("mode"));
            if (temp == null && (mode == null || mode.isBlank())) continue; // 无温度/模式 → 无设备参数
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("step_index", i);
            p.put("device_type", inferDeviceType(mode, temp));
            p.put("mode", mode);
            p.put("temperature", temp);
            p.put("duration_min", intOf(st.get("timerMin")));
            p.put("note", strOf(st.get("summary")));
            params.add(p);
        }
        if (params.isEmpty()) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device_params", params);
        saveDeviceParamsFromRecipe(recipeId, data);
    }

    /** 由步骤模式/温度推断设备类型（与流水线 LLM 产出对齐：oven/steamer/cooktop/airfryer） */
    private String inferDeviceType(String mode, Integer temp) {
        if (mode != null) {
            if (mode.contains("蒸")) return "steamer";
            if (mode.contains("炸")) return "airfryer";
            if (mode.contains("烤") || mode.contains("烘焙") || mode.contains("预热")) return "oven";
        }
        if (temp != null && temp >= 150) return "oven";
        return "cooktop";
    }

    // ---------------- 事件处理 ----------------

    /** 厂商事件 → 解析设备 → 入库 + WS 推送 */
    private void handleGatewayEvent(DeviceEventMessage evt) {
        if (evt.vendorDeviceId == null) return;
        KitchenDevice dev = deviceRepository.findByVendorAndVendorDeviceId("mock", evt.vendorDeviceId);
        if (dev == null) {
            log.warn("收到未绑定设备事件 vendorDeviceId={}", evt.vendorDeviceId);
            return;
        }
        eventService.recordEvent(dev, evt.eventType, evt.payload);
    }

    // ---------------- helpers ----------------

    private KitchenDevice requireOwnedDevice(Long userId, Long deviceId) {
        KitchenDevice dev = deviceRepository.selectById(deviceId);
        if (dev == null) throw new IllegalArgumentException("设备不存在");
        if (dev.getOrgId() != null) {
            if (!orgAccessService.isMember(userId, dev.getOrgId())) throw new SecurityException("无权操作该设备");
        } else if (!dev.getUserId().equals(userId)) {
            throw new SecurityException("无权操作该设备");
        }
        return dev;
    }

    private String resolveAction(String mode) {
        if (mode == null) return "start";
        return switch (mode) {
            case "蒸", "清蒸" -> "steam";
            case "空气炸", "空炸" -> "air_fry";
            case "烘焙", "烤", "烧烤", "预热" -> "bake";
            default -> "start";
        };
    }

    private Integer intOf(Object o) {
        if (o == null) return null;
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private String serialize(DeviceCommand cmd) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", cmd.action);
            m.put("mode", cmd.mode);
            m.put("temperature", cmd.temperature);
            m.put("durationMin", cmd.durationMin);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    private DeviceCommand deserialize(String json) {
        DeviceCommand cmd = new DeviceCommand();
        try {
            Map<String, Object> m = objectMapper.readValue(json, Map.class);
            cmd.action = m.get("action") == null ? null : String.valueOf(m.get("action"));
            cmd.mode = m.get("mode") == null ? null : String.valueOf(m.get("mode"));
            cmd.temperature = intOf(m.get("temperature"));
            cmd.durationMin = intOf(m.get("durationMin"));
        } catch (Exception e) { /* ignore */ }
        return cmd;
    }

    // ---------------- P1：厨电调度共用 helper ----------------

    /** 按设备类型找当前用户可用的在线设备（企业成员用 org_id；个人用 user_id）；无则 null */
    public KitchenDevice findOnlineDeviceFor(Long userId, String deviceType) {
        return deviceRepository.findOnlineByType(
                orgAccessService.effectiveOrgId(userId), userId, deviceType);
    }

    /** 按设备类型找在线设备并下发（供厨电调度到点触发）；返回下发日志 id 或抛异常 */
    public Map<String, Object> dispatchParam(Long userId, Long deviceId, Long paramId) {
        return sendCommand(userId, deviceId, paramId, null);
    }
}
