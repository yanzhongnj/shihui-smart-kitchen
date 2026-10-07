package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiCreationFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiCreationFeedbackRepository extends BaseMapper<AiCreationFeedback> {

    @Select("SELECT * FROM ai_creation_feedbacks WHERE creation_id = #{creationId} ORDER BY created_at DESC")
    List<AiCreationFeedback> findByCreationId(@Param("creationId") Long creationId);
}
