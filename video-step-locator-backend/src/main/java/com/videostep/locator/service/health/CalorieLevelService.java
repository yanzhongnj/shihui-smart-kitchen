package com.videostep.locator.service.health;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡路里科学分级（v0.2，借鉴 HowToCookPHP 热量"温度计"）。
 * 阈值可配置：health.nutrition.calorie-thresholds.low / .medium（默认 300/600）。
 */
@Service
public class CalorieLevelService {

    public enum Level {
        LOW("低热量", "green"),
        MEDIUM("中热量", "orange"),
        HIGH("高热量", "red");

        @Getter private final String label;
        @Getter private final String color;

        Level(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

    @Value("${health.nutrition.calorie-thresholds.low:300}")
    private int lowThreshold;

    @Value("${health.nutrition.calorie-thresholds.medium:600}")
    private int mediumThreshold;

    /** 单值分级 */
    public Level levelFor(double calories) {
        if (calories <= lowThreshold) return Level.LOW;
        if (calories <= mediumThreshold) return Level.MEDIUM;
        return Level.HIGH;
    }

    public Map<String, Object> levelInfo(double calories) {
        Level lv = levelFor(calories);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calories", Math.round(calories));
        m.put("level", lv.name());
        m.put("label", lv.getLabel());
        m.put("color", lv.getColor());
        return m;
    }

    /** 分级配置列表（含阈值边界） */
    public List<Map<String, Object>> levels() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(levelRange("LOW", Level.LOW, 0, lowThreshold));
        out.add(levelRange("MEDIUM", Level.MEDIUM, lowThreshold, mediumThreshold));
        out.add(levelRange("HIGH", Level.HIGH, mediumThreshold, null));
        return out;
    }

    private Map<String, Object> levelRange(String name, Level lv, Integer min, Integer max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", name);
        m.put("label", lv.getLabel());
        m.put("color", lv.getColor());
        m.put("min", min);
        m.put("max", max);
        return m;
    }
}
