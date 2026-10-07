package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiCreationMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiCreationMessageRepository extends BaseMapper<AiCreationMessage> {

    @Select("SELECT * FROM ai_creation_messages WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<AiCreationMessage> findBySessionId(@Param("sessionId") Long sessionId);

    @Select("SELECT COUNT(*) FROM ai_creation_messages WHERE session_id = #{sessionId}")
    long countBySessionId(@Param("sessionId") Long sessionId);

    @Select("SELECT * FROM ai_creation_messages WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT 1")
    AiCreationMessage findLastBySessionId(@Param("sessionId") Long sessionId);
}
