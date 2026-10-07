package com.videostep.locator.service.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.CookingPlan;
import com.videostep.locator.entity.CookingPlanItem;
import com.videostep.locator.entity.KitchenDevice;
import com.videostep.locator.entity.RecipeDeviceParam;
import com.videostep.locator.repository.CookingPlanItemRepository;
import com.videostep.locator.repository.CookingPlanRepository;
import com.videostep.locator.repository.RecipeDeviceParamRepository;
import com.videostep.locator.service.OrgAccessService;
import com.videostep.locator.service.device.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 厨电智能调度（做饭计划执行闭环 P1，设计文档 §5.4）。
 *
 *  - 单菜单设备时间轴：按条目 recipeId 取 recipe_device_params（预热→工作→完成），
 *    从指定/推断起始时刻起按 durationMin 依次累计每个步骤的开始时间；
 *  - 到点触发 DeviceCommandService 下发（异步线程池，指令日志/事件/WS 推送复用中控）；
 *  - 设备不可用（无绑定/离线）→ 自动降级 exec_mode=self（线路 B 视频参考），计划不中断；
 *  - 重启容错（v0.3 决策 7.7-6）：时间轴为内存态，@PostConstruct 扫描未完成 appliance
 *    条目，已过时间点的步骤标记 expired，未到时间点重新排程到点下发。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitchenSchedulerService {

    private final CookingPlanItemRepository itemRepository;
    private final CookingPlanRepository planRepository;
    private final RecipeDeviceParamRepository paramRepository;
    private final DeviceCommandService deviceCommandService;
    private final OrgAccessService orgAccessService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 调度线程池（内存态，daemon） */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "kitchen-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** itemId → 各步骤的下发任务（重启即清空，由 rescheduleAfterRestart 重建） */
    private final Map<Long, List<ScheduledFuture<?>>> tasks = new ConcurrentHashMap<>();

    @Value("${plan.scheduler.enabled:true}")
    private boolean enabled;

    @Value("${plan.scheduler.degrade-to-self:true}")
    private boolean degradeToSelf;

    // ================= 编排 =================

    /**
     * 为一条计划条目编排厨电时间轴并到点下发。
     * req: {startAt?: "HH:mm"}（可选，默认按餐次推断；已过则取当前+2 分钟便于演示）
     * 返回 {execMode, device?, scheduleJson, message}
     */
    public Map<String, Object> scheduleItem(Long userId, Long itemId, String startAt) {
        CookingPlanItem item = requireOwnedItem(userId, itemId);
        if (!enabled) {
            throw new IllegalArgumentException("厨电调度未开启");
        }
        if (item.getRecipeId() == null) {
            throw new IllegalArgumentException("该条目未关联菜谱，无法编排厨电时间轴");
        }
        List<RecipeDeviceParam> params = paramRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RecipeDeviceParam>()
                        .eq(RecipeDeviceParam::getRecipeId, item.getRecipeId())
                        .orderByAsc(RecipeDeviceParam::getStepIndex));
        if (params.isEmpty()) {
            throw new IllegalArgumentException("该菜谱暂无厨电设备参数（步骤缺少温度/模式），请先在菜谱详情下发或补充");
        }

        // 起始时刻：显式 startAt > 餐次推断 > 当前+2min
        LocalTime start = parseStartAt(startAt, item.getMealType());

        // 逐设备类型编排：P1 单菜单设备——所有步骤尽可能用同一在线设备（按第一个 deviceType 匹配）；
        // 步骤中 deviceType 与首选设备不符的步骤仍下发到该设备（简化，多设备协同为远期）。
        String firstType = params.get(0).getDeviceType();
        KitchenDevice dev = deviceCommandService.findOnlineDeviceFor(userId, firstType);

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("execMode", "appliance");
        schedule.put("status", "scheduled");
        schedule.put("createdAt", LocalDateTime.now().toString());
        schedule.put("startAt", start.toString());
        schedule.put("deviceId", dev != null ? dev.getId() : null);
        schedule.put("deviceType", firstType);
        schedule.put("deviceNickname", dev != null ? dev.getNickname() : null);

        // 累计起始时刻，生成步骤时间轴
        LocalDateTime cursor = LocalDateTime.now().toLocalDate().atTime(start);
        if (cursor.isBefore(LocalDateTime.now().plusMinutes(1))) {
            cursor = LocalDateTime.now().plusMinutes(2); // 已过 → 顺延 2 分钟（便于演示）
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (RecipeDeviceParam p : params) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("stepIndex", p.getStepIndex());
            st.put("action", resolveAction(p.getMode()));
            st.put("mode", p.getMode());
            st.put("temperature", p.getTemperature());
            st.put("durationMin", p.getDurationMin());
            st.put("note", p.getNote());
            st.put("startAt", cursor.toString());
            st.put("status", "pending");
            steps.add(st);
            // 步骤持续到下一步开始（模拟：预热 3s、工作步骤按 durationMin 计）
            cursor = cursor.plusMinutes(p.getDurationMin() != null ? p.getDurationMin() : 5);
        }
        schedule.put("steps", steps);

        // 无设备 → 降级线路 B
        if (dev == null && degradeToSelf) {
            item.setExecMode("self");
            item.setScheduleJson(toJson(schedule));
            itemRepository.updateById(item);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("itemId", itemId);
            out.put("execMode", "self");
            out.put("message", "未绑定可用" + deviceTypeName(firstType) + "设备，已降级为线路 B（视频参考）");
            out.put("schedule", schedule);
            return out;
        }
        if (dev == null) {
            throw new IllegalArgumentException("未绑定可用" + deviceTypeName(firstType) + "设备");
        }

        item.setExecMode("appliance");
        item.setScheduleJson(toJson(schedule));
        itemRepository.updateById(item);

        scheduleSteps(userId, itemId, schedule, steps, dev);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", itemId);
        out.put("execMode", "appliance");
        out.put("deviceId", dev.getId());
        out.put("deviceNickname", dev.getNickname());
        out.put("message", "厨电调度已编排：" + dev.getNickname() + "（" + steps.size() + " 步，起始 "
                + start + "，每步到点自动下发）");
        out.put("schedule", schedule);
        return out;
    }

    /** 编排各步骤到点下发（供 scheduleItem 与重启重排共用） */
    private void scheduleSteps(Long userId, Long itemId, Map<String, Object> schedule, List<Map<String, Object>> steps,
                               KitchenDevice dev) {
        // 取消旧的
        cancelTasks(itemId);
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        for (Map<String, Object> st : steps) {
            if (!"pending".equals(st.get("status"))) continue;
            LocalDateTime at = LocalDateTime.parse(String.valueOf(st.get("startAt")));
            long delayMs = Math.max(0, java.time.Duration.between(LocalDateTime.now(), at).toMillis());
            Long stepIdx = ((Number) st.get("stepIndex")).longValue();
            ScheduledFuture<?> f = scheduler.schedule(() -> dispatchStep(userId, itemId, stepIdx, st, dev),
                    delayMs, TimeUnit.MILLISECONDS);
            futures.add(f);
        }
        tasks.put(itemId, futures);
    }

    private void dispatchStep(Long userId, Long itemId, Long stepIndex, Map<String, Object> st, KitchenDevice dev) {
        try {
            CookingPlanItem item = itemRepository.selectById(itemId);
            if (item == null || item.getDone() != null && item.getDone() == 1) return;
            String action = String.valueOf(st.get("action"));
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("action", action);
            cmd.put("mode", st.get("mode"));
            cmd.put("temperature", st.get("temperature"));
            cmd.put("durationMin", st.get("durationMin"));
            // 校验设备仍属主（个人设备用自身 userId；企业设备重启场景回退 dev.userId）
            Long caller = userId != null ? userId : dev.getUserId();
            deviceCommandService.sendCommand(caller, dev.getId(), null, cmd);
            st.put("status", "sent");
            st.put("dispatchedAt", LocalDateTime.now().toString());
            // 回写 schedule_json
            updateScheduleJson(itemId, st, "sent");
            log.info("[厨电调度] item={} step={} 已下发 {}", itemId, stepIndex, action);
        } catch (Exception e) {
            log.warn("[厨电调度] item={} step={} 下发失败: {}", itemId, stepIndex, e.getMessage());
            st.put("status", "failed");
            st.put("error", e.getMessage());
            updateScheduleJson(itemId, st, "failed");
        }
    }

    /** 更新某步骤状态并回写 schedule_json */
    private void updateScheduleJson(Long itemId, Map<String, Object> step, String status) {
        try {
            CookingPlanItem item = itemRepository.selectById(itemId);
            if (item == null || item.getScheduleJson() == null) return;
            Map<String, Object> schedule = objectMapper.readValue(item.getScheduleJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            List<Map<String, Object>> steps = (List<Map<String, Object>>) schedule.get("steps");
            for (Map<String, Object> s : steps) {
                if (s.get("stepIndex") != null && s.get("stepIndex").equals(step.get("stepIndex"))) {
                    s.putAll(step);
                    break;
                }
            }
            boolean allDone = steps.stream().allMatch(s -> "sent".equals(s.get("status")) || "failed".equals(s.get("status")));
            schedule.put("status", allDone ? "done" : "running");
            item.setScheduleJson(objectMapper.writeValueAsString(schedule));
            itemRepository.updateById(item);
        } catch (Exception e) {
            log.warn("[厨电调度] 回写状态失败 item={}: {}", itemId, e.getMessage());
        }
    }

    // ================= 取消 / 查询 =================

    public Map<String, Object> cancelSchedule(Long userId, Long itemId) {
        CookingPlanItem item = requireOwnedItem(userId, itemId);
        cancelTasks(itemId);
        if (item.getScheduleJson() != null) {
            try {
                Map<String, Object> schedule = objectMapper.readValue(item.getScheduleJson(),
                        new TypeReference<LinkedHashMap<String, Object>>() {});
                schedule.put("status", "cancelled");
                item.setScheduleJson(objectMapper.writeValueAsString(schedule));
            } catch (Exception ignored) { }
        }
        itemRepository.updateById(item);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", itemId);
        out.put("cancelled", true);
        out.put("message", "厨电调度已取消");
        return out;
    }

    public Map<String, Object> getSchedule(Long userId, Long itemId) {
        CookingPlanItem item = requireOwnedItem(userId, itemId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", itemId);
        out.put("execMode", item.getExecMode());
        out.put("recipeTitle", item.getRecipeTitle());
        if (item.getScheduleJson() != null) {
            try {
                out.put("schedule", objectMapper.readValue(item.getScheduleJson(),
                        new TypeReference<LinkedHashMap<String, Object>>() {}));
            } catch (Exception e) {
                out.put("schedule", null);
            }
        } else {
            out.put("schedule", null);
        }
        return out;
    }

    // ================= 重启容错 =================

    /** 重启后：扫描 exec_mode=appliance 且未完成的条目，重排时间轴（已过步骤标 expired） */
    @PostConstruct
    public void rescheduleAfterRestart() {
        try {
            List<CookingPlanItem> items = itemRepository.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CookingPlanItem>()
                            .eq(CookingPlanItem::getExecMode, "appliance")
                            .eq(CookingPlanItem::getDone, 0)
                            .isNotNull(CookingPlanItem::getScheduleJson));
            int scheduled = 0;
            for (CookingPlanItem item : items) {
                try {
                    Map<String, Object> schedule = objectMapper.readValue(item.getScheduleJson(),
                            new TypeReference<LinkedHashMap<String, Object>>() {});
                    List<Map<String, Object>> steps = (List<Map<String, Object>>) schedule.get("steps");
                    if (steps == null || steps.isEmpty()) continue;
                    Object deviceIdObj = schedule.get("deviceId");
                    if (!(deviceIdObj instanceof Number)) continue;
                    // 重启场景无请求上下文：从计划属主取 userId 匹配设备
                    CookingPlan ownerPlan = planRepository.selectById(item.getPlanId());
                    if (ownerPlan == null) continue;
                    KitchenDevice dev = deviceCommandService.findOnlineDeviceFor(
                            ownerPlan.getUserId(),
                            String.valueOf(schedule.get("deviceType")));
                    if (dev == null) {
                        schedule.put("status", "degraded");
                        schedule.put("message", "重启后设备不可用，降级线路 B");
                        item.setScheduleJson(objectMapper.writeValueAsString(schedule));
                        itemRepository.updateById(item);
                        continue;
                    }
                    // 标记已过时间点的步骤 expired，重新排程未到时间点的
                    LocalDateTime now = LocalDateTime.now();
                    for (Map<String, Object> st : steps) {
                        if ("pending".equals(st.get("status"))) {
                            LocalDateTime at = LocalDateTime.parse(String.valueOf(st.get("startAt")));
                            st.put("status", at.isBefore(now) ? "expired" : "pending");
                        }
                    }
                    item.setScheduleJson(objectMapper.writeValueAsString(schedule));
                    itemRepository.updateById(item);
                    scheduleSteps(null, item.getId(), schedule, steps, dev);
                    scheduled++;
                } catch (Exception e) {
                    log.warn("[厨电调度] 重启重排失败 item={}: {}", item.getId(), e.getMessage());
                }
            }
            if (scheduled > 0) log.info("[厨电调度] 重启后已重排 {} 条厨电调度", scheduled);
        } catch (Exception e) {
            log.warn("[厨电调度] 重启扫描失败: {}", e.getMessage());
        }
    }

    // ================= helpers =================

    private void cancelTasks(Long itemId) {
        List<ScheduledFuture<?>> futures = tasks.remove(itemId);
        if (futures != null) {
            futures.forEach(f -> f.cancel(false));
        }
    }

    private CookingPlanItem requireOwnedItem(Long userId, Long itemId) {
        CookingPlanItem item = itemRepository.selectById(itemId);
        if (item == null) throw new IllegalArgumentException("计划条目不存在");
        CookingPlan plan = planRepository.selectById(item.getPlanId());
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该计划条目");
        }
        return item;
    }

    private LocalTime parseStartAt(String startAt, String mealType) {
        if (startAt != null && !startAt.isBlank()) {
            try {
                return LocalTime.parse(startAt.trim());
            } catch (DateTimeParseException e) {
                // fallthrough
            }
        }
        return switch (mealType == null ? "" : mealType) {
            case "breakfast" -> LocalTime.of(7, 30);
            case "lunch" -> LocalTime.of(12, 0);
            case "snack" -> LocalTime.of(15, 0);
            default -> LocalTime.of(18, 30); // dinner
        };
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

    private String deviceTypeName(String t) {
        return switch (t == null ? "" : t) {
            case "oven" -> "烤箱";
            case "steamer" -> "蒸箱";
            case "cooktop" -> "灶具";
            case "airfryer" -> "空气炸锅";
            default -> t == null ? "厨电" : t;
        };
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
