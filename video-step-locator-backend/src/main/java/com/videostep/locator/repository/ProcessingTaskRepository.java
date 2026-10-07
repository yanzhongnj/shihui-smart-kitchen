package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.ProcessingTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ProcessingTaskRepository extends BaseMapper<ProcessingTask> {
    @Select("SELECT * FROM processing_tasks WHERE status = #{status} ORDER BY created_at ASC")
    List<ProcessingTask> findByStatusOrderByCreatedAtAsc(@Param("status") String status);

    @Select("SELECT * FROM processing_tasks WHERE video_id = #{videoId} ORDER BY created_at DESC")
    List<ProcessingTask> findByVideoIdOrderByCreatedAtDesc(@Param("videoId") Long videoId);

    @Select("SELECT * FROM processing_tasks WHERE video_id = #{videoId} ORDER BY created_at DESC LIMIT 1")
    Optional<ProcessingTask> findFirstByVideoIdOrderByCreatedAtDesc(@Param("videoId") Long videoId);

    @Select("SELECT COUNT(*) FROM processing_tasks WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}