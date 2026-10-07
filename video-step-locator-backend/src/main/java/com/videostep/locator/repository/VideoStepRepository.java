package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.VideoStep;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface VideoStepRepository extends BaseMapper<VideoStep> {
    @Select("SELECT * FROM video_steps WHERE video_id = #{videoId} ORDER BY step_index ASC")
    List<VideoStep> findByVideoIdOrderByStepIndexAsc(@Param("videoId") Long videoId);

    @Delete("DELETE FROM video_steps WHERE video_id = #{videoId}")
    void deleteByVideoId(@Param("videoId") Long videoId);
}