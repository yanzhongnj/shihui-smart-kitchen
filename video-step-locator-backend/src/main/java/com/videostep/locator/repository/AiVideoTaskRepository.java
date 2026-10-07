package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiVideoTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
public interface AiVideoTaskRepository extends BaseMapper<AiVideoTask> {

    @Select("SELECT * FROM ai_video_tasks WHERE creation_id = #{creationId} ORDER BY step_index ASC")
    List<AiVideoTask> findByCreationId(@Param("creationId") Long creationId);

    @Select("SELECT * FROM ai_video_tasks WHERE prompt = #{prompt} AND status = 'completed' "
            + "AND video_path IS NOT NULL ORDER BY id ASC LIMIT 1")
    AiVideoTask findCachedByPrompt(@Param("prompt") String prompt);
}
