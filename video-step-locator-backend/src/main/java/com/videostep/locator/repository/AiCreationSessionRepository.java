package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiCreationSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiCreationSessionRepository extends BaseMapper<AiCreationSession> {

    @Select("SELECT * FROM ai_creation_sessions WHERE user_id = #{userId} ORDER BY updated_at DESC")
    List<AiCreationSession> findByUserId(@Param("userId") Long userId);
}
