package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.Org;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrgRepository extends BaseMapper<Org> {
    @Select("SELECT * FROM orgs WHERE code = #{code} LIMIT 1")
    Org findByCode(@Param("code") String code);
}
