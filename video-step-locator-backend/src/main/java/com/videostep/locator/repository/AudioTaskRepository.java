package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AudioTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface AudioTaskRepository extends BaseMapper<AudioTask> {
    @Select("SELECT * FROM audio_tasks WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<AudioTask> findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM audio_tasks WHERE status = #{status} ORDER BY created_at ASC")
    List<AudioTask> findByStatus(@Param("status") String status);
}
