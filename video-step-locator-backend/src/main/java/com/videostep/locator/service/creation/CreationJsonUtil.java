package com.videostep.locator.service.creation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 酿味 JSON 序列化/解析工具（创作字段以 JSON 文本存储）。
 */
public final class CreationJsonUtil {

    private static final ObjectMapper OM = new ObjectMapper();

    private CreationJsonUtil() {
    }

    /** JsonNode 数组 → List<String>（非数组返回空列表） */
    public static List<String> arr(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        }
        return out;
    }

    /** 对象 → JSON 文本；失败返回 null */
    public static String toJson(Object o) {
        try {
            return OM.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 存储的 JSON 数组文本 → List<String> */
    public static List<String> parseStringArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode n = OM.readTree(json);
            if (n.isArray()) {
                n.forEach(x -> out.add(x.asText()));
            } else {
                out.add(json);
            }
        } catch (Exception e) {
            out.addAll(Arrays.stream(json.split("[,，、]")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        return out;
    }

    /** 存储的 steps JSON 数组文本 → List<Map> */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseSteps(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode n = OM.readTree(json);
            if (n.isArray()) {
                for (JsonNode x : n) {
                    out.add(OM.convertValue(x, Map.class));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JsonNode read(String json) {
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            return OM.createObjectNode();
        }
    }
}
