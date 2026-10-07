package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.ProcessingLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ProcessingLogRepository extends BaseMapper<ProcessingLog> {
    @Select("SELECT * FROM processing_logs WHERE task_id = #{taskId} ORDER BY created_at ASC")
    List<ProcessingLog> findByTaskIdOrderByCreatedAtAsc(@Param("taskId") Long taskId);

    @Select("SELECT * FROM processing_logs WHERE task_id = #{taskId} ORDER BY created_at DESC LIMIT #{limit}")
    List<ProcessingLog> findRecentByTaskId(@Param("taskId") Long taskId, @Param("limit") int limit);
}
