package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.DownloadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface DownloadTaskRepository extends BaseMapper<DownloadTask> {
    @Select("SELECT * FROM download_tasks WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<DownloadTask> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Select("SELECT * FROM download_tasks WHERE device_id = #{deviceId} ORDER BY created_at DESC")
    List<DownloadTask> findByDeviceIdOrderByCreatedAtDesc(@Param("deviceId") String deviceId);

    @Select("SELECT COUNT(*) FROM download_tasks WHERE device_id = #{deviceId} AND video_id = #{videoId}")
    long countByDeviceIdAndVideoId(@Param("deviceId") String deviceId, @Param("videoId") Long videoId);

    @Select("SELECT * FROM download_tasks WHERE status = #{status} ORDER BY created_at ASC")
    List<DownloadTask> findByStatusOrderByCreatedAtAsc(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM download_tasks WHERE user_id = #{userId} AND created_at >= #{start} AND created_at < #{end}")
    long countInRange(@Param("userId") Long userId,
                      @Param("start") java.time.LocalDateTime start,
                      @Param("end") java.time.LocalDateTime end);

    /** 取某视频最近的下载任务（用于菜谱 source_url 溯源回填） */
    @Select("SELECT * FROM download_tasks WHERE video_id = #{videoId} AND source_url IS NOT NULL ORDER BY id DESC LIMIT 1")
    DownloadTask findFirstByVideoIdWithUrl(@Param("videoId") Long videoId);
}
