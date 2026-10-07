package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiImageTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiImageTaskRepository extends BaseMapper<AiImageTask> {

    @Select("SELECT * FROM ai_image_tasks WHERE creation_id = #{creationId} ORDER BY id ASC")
    List<AiImageTask> findByCreationId(@Param("creationId") Long creationId);
}
