package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.FoodSafetyRuleCandidate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食物安全规则候选/审核队列仓储。
 */
@Mapper
public interface FoodSafetyRuleCandidateRepository extends BaseMapper<FoodSafetyRuleCandidate> {
}
