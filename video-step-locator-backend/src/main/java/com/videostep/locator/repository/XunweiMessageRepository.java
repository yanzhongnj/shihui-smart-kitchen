package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.XunweiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface XunweiMessageRepository extends BaseMapper<XunweiMessage> {
    @Select("SELECT * FROM xunwei_messages WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<XunweiMessage> findBySessionId(Long sessionId);

    @Select("SELECT * FROM xunwei_messages WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{limit}")
    List<XunweiMessage> findRecentBySessionId(Long sessionId, int limit);

    @Select("SELECT COUNT(*) FROM xunwei_messages WHERE session_id = #{sessionId}")
    long countBySessionId(Long sessionId);

    @Select("SELECT * FROM xunwei_messages WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT 1")
    XunweiMessage findLastBySessionId(Long sessionId);
}
