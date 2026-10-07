package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiCreationShare;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiCreationShareRepository extends BaseMapper<AiCreationShare> {

    @Select("SELECT * FROM ai_creation_shares WHERE code = #{code} LIMIT 1")
    AiCreationShare findByCode(@Param("code") String code);
}
