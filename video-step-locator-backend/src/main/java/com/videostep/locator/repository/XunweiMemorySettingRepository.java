package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.XunweiMemorySetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface XunweiMemorySettingRepository extends BaseMapper<XunweiMemorySetting> {
    @Select("SELECT * FROM xunwei_memory_settings WHERE user_id = #{userId} LIMIT 1")
    XunweiMemorySetting findByUserId(Long userId);
}
