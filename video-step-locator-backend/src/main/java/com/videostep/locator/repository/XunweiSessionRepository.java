package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.XunweiSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface XunweiSessionRepository extends BaseMapper<XunweiSession> {
    @Select("SELECT * FROM xunwei_sessions WHERE user_id = #{userId} ORDER BY updated_at DESC")
    List<XunweiSession> findByUserId(Long userId);
}
