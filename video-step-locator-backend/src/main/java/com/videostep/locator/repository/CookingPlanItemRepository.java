package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.CookingPlanItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CookingPlanItemRepository extends BaseMapper<CookingPlanItem> {

    @Select("SELECT * FROM cooking_plan_items WHERE plan_id = #{planId} ORDER BY day_offset ASC, id ASC")
    List<CookingPlanItem> findByPlanId(@Param("planId") Long planId);

    @Select("SELECT COUNT(*) FROM cooking_plan_items WHERE plan_id = #{planId} AND done = 1")
    long countDone(@Param("planId") Long planId);
}
