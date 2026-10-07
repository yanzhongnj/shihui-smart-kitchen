package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.UserPreference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserPreferenceRepository extends BaseMapper<UserPreference> {
    @Select("SELECT * FROM user_preferences WHERE user_id = #{userId} LIMIT 1")
    UserPreference findByUserId(@Param("userId") Long userId);
}
