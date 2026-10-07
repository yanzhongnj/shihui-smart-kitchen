package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.FoodSafetyRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食物安全规则仓储。查询在 SafetyCheckService 用 LambdaQueryWrapper 实现。
 */
@Mapper
public interface FoodSafetyRuleRepository extends BaseMapper<FoodSafetyRule> {
}
