package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.FoodSafetyRuleFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食物安全规则反馈仓储。
 */
@Mapper
public interface FoodSafetyRuleFeedbackRepository extends BaseMapper<FoodSafetyRuleFeedback> {
}
