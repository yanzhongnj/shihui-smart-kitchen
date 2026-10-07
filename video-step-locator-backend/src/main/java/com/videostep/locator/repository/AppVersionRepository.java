package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AppVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppVersionRepository extends BaseMapper<AppVersion> {
    @Select("SELECT * FROM app_versions WHERE platform = #{platform} AND enabled = 1 ORDER BY build DESC LIMIT 1")
    AppVersion findLatestByPlatform(@Param("platform") String platform);
}
