package com.videostep.locator.service.ingredient;

import com.videostep.locator.service.xunwei.XunweiVisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拍照反查（P1，设计文档 §4.3，差异化升级）。
 * 复用 XunweiVisionService（qwen-vl）识别食材 → 自动生成标签 → ReverseLookupService 反查。
 * 识别结果允许前端手动增删（"AI 识别了鸡腿、土豆，还可添加"）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoLookupService {

    private final XunweiVisionService xunweiVisionService;
    private final ReverseLookupService reverseLookupService;

    public Map<String, Object> photoLookup(byte[] imageBytes, String filename, Long userId) {
        List<String> ingredients = xunweiVisionService.recognizeIngredients(imageBytes, filename);
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("未从图片中识别到食材，请换一张更清晰的食材照片重试");
        }
        Map<String, Object> reverse = reverseLookupService.reverse(ingredients, null, null, null, null, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ingredients", ingredients);
        out.put("summary", "识别到 " + ingredients.size() + " 种食材：" + String.join("、", ingredients));
        out.putAll(reverse);
        return out;
    }
}
