package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.UserBadge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserBadgeRepository extends BaseMapper<UserBadge> {

    @Select("SELECT badge_code FROM user_badges WHERE user_id = #{userId}")
    List<String> findBadgeCodesByUser(@Param("userId") Long userId);
}
