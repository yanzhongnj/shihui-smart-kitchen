package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.OrgPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrgPlanRepository extends BaseMapper<OrgPlan> {
    @Select("SELECT * FROM org_plans WHERE code = #{code} AND status = 1 LIMIT 1")
    OrgPlan findEnabledByCode(@Param("code") String code);
}
