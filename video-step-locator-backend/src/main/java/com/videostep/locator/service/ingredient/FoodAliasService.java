package com.videostep.locator.service.ingredient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.FoodAlias;
import com.videostep.locator.repository.FoodAliasRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 食材同义词服务（反查归一 + 营养计算匹配共用，设计文档 §6.2）。
 * 别名→规范名映射缓存；新增后 reloadCache 保证即时生效。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodAliasService {

    private final FoodAliasRepository aliasRepository;

    /** alias → canonical（缓存） */
    private volatile Map<String, String> cache = Map.of();

    @PostConstruct
    public void init() {
        reloadCache();
    }

    public synchronized void reloadCache() {
        Map<String, String> m = new HashMap<>();
        for (FoodAlias a : aliasRepository.selectList(null)) {
            m.put(a.getAlias(), a.getCanonical());
        }
        this.cache = m;
        log.info("FoodAliasService ready, aliases={}", m.size());
    }

    /**
     * 别名归一：命中 food_aliases 返回规范名；未命中原样返回。
     * 先去除首尾空白与标点（语音转写常带句尾「。」等，作为菜名/食材名需清理）。
     */
    public String normalize(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.isEmpty()) return s;
        int start = 0, end = s.length();
        while (start < end && PUNCT.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && PUNCT.indexOf(s.charAt(end - 1)) >= 0) end--;
        if (start > 0 || end < s.length()) s = s.substring(start, end);
        if (s.isEmpty()) return s;
        String canonical = cache.get(s);
        return canonical != null ? canonical : s;
    }

    /** 首尾标点集合（句号/逗号/感叹/问号/省略/引号/括号等） */
    private static final String PUNCT =
            "。．.！!？?，,、；;：:…～~·•\"'‘’“”()（）<>《》「」『』【】";

    public List<FoodAlias> list() {
        return aliasRepository.selectList(new LambdaQueryWrapper<FoodAlias>()
                .orderByAsc(FoodAlias::getCanonical).orderByAsc(FoodAlias::getAlias));
    }

    /** 新增同义词（alias 唯一，source 默认 manual）；成功后刷新缓存 */
    public FoodAlias create(String alias, String canonical, String source) {
        if (alias == null || alias.isBlank() || canonical == null || canonical.isBlank()) {
            throw new IllegalArgumentException("别名与规范名不能为空");
        }
        String a = alias.trim();
        String c = canonical.trim();
        if (a.equals(c)) throw new IllegalArgumentException("别名与规范名不能相同");
        if (aliasRepository.findByAlias(a) != null) throw new IllegalArgumentException("别名「" + a + "」已存在");
        FoodAlias fa = FoodAlias.builder()
                .alias(a)
                .canonical(c)
                .source(source == null || source.isBlank() ? "manual" : source.trim())
                .build();
        aliasRepository.insert(fa);
        reloadCache();
        return fa;
    }

    /** 删除同义词（管理端）；不存在报错，成功后刷新缓存 */
    public void deleteAlias(Long id) {
        if (id == null || aliasRepository.selectById(id) == null) {
            throw new IllegalArgumentException("同义词不存在");
        }
        aliasRepository.deleteById(id);
        reloadCache();
    }
}
