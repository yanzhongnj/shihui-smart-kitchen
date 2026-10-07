package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.BrandConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BrandConfigRepository extends BaseMapper<BrandConfig> {
    @Select("SELECT * FROM brand_configs WHERE org_id = #{orgId} LIMIT 1")
    BrandConfig findByOrgId(@Param("orgId") Long orgId);
}
