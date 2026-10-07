package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiCreationVideo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiCreationVideoRepository extends BaseMapper<AiCreationVideo> {

    @Select("SELECT * FROM ai_creation_videos WHERE creation_id = #{creationId} LIMIT 1")
    AiCreationVideo findByCreationId(@Param("creationId") Long creationId);

    @Select("SELECT * FROM ai_creation_videos WHERE status = 'generating'")
    java.util.List<AiCreationVideo> findGenerating();
}
