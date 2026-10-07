package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.ApiClient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApiClientRepository extends BaseMapper<ApiClient> {
    @Select("SELECT * FROM api_clients WHERE app_key = #{appKey} LIMIT 1")
    ApiClient findByAppKey(@Param("appKey") String appKey);
}
