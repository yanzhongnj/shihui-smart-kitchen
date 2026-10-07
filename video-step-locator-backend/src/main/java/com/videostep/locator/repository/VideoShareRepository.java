package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.VideoShare;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Optional;

@Mapper
public interface VideoShareRepository extends BaseMapper<VideoShare> {
    @Select("SELECT * FROM video_shares WHERE video_id = #{videoId}")
    List<VideoShare> findByVideoId(@Param("videoId") Long videoId);

    @Select("SELECT * FROM video_shares WHERE shared_with = #{userId}")
    List<VideoShare> findBySharedWithId(@Param("userId") Long userId);

    @Select("SELECT * FROM video_shares WHERE video_id = #{videoId} AND shared_with = #{userId}")
    Optional<VideoShare> findByVideoIdAndSharedWithId(@Param("videoId") Long videoId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM video_shares WHERE video_id = #{videoId} AND shared_with = #{userId}")
    boolean existsByVideoIdAndSharedWithId(@Param("videoId") Long videoId, @Param("userId") Long userId);

    @Delete("DELETE FROM video_shares WHERE video_id = #{videoId} AND shared_with = #{userId}")
    void deleteByVideoIdAndSharedWithId(@Param("videoId") Long videoId, @Param("userId") Long userId);

    /** 未过期定向分享数（用于「已共享」锁定判定） */
    @Select("SELECT COUNT(*) FROM video_shares WHERE video_id = #{videoId} AND (expires_at IS NULL OR expires_at > NOW())")
    long countActiveByVideoId(@Param("videoId") Long videoId);
}