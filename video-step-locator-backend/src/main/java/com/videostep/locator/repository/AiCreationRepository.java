package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiCreation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiCreationRepository extends BaseMapper<AiCreation> {

    @Select("SELECT * FROM ai_creations WHERE user_id = #{userId} ORDER BY updated_at DESC LIMIT #{offset}, #{size}")
    List<AiCreation> pageByUser(@Param("userId") Long userId,
                                @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM ai_creations WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM ai_creations WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT 1")
    AiCreation findBySessionId(@Param("sessionId") Long sessionId);
}
