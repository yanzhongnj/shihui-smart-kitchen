package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.VoiceCommandLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface VoiceCommandLogRepository extends BaseMapper<VoiceCommandLog> {
    @Select("SELECT * FROM voice_commands_log WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<VoiceCommandLog> findBySessionIdOrderByCreatedAtAsc(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM voice_commands_log WHERE video_id = #{videoId} ORDER BY created_at DESC")
    List<VoiceCommandLog> findByVideoIdOrderByCreatedAtDesc(@Param("videoId") Long videoId);
}