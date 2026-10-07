package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.VideoReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface VideoReviewRepository extends BaseMapper<VideoReview> {
    @Select("SELECT * FROM video_reviews WHERE video_id = #{videoId} ORDER BY created_at DESC")
    List<VideoReview> findByVideoId(@Param("videoId") Long videoId);

    @Select("SELECT AVG(rating) FROM video_reviews WHERE video_id = #{videoId}")
    Double avgRatingByVideoId(@Param("videoId") Long videoId);

    @Select("SELECT COUNT(*) FROM video_reviews WHERE video_id = #{videoId}")
    long countByVideoId(@Param("videoId") Long videoId);

    // ---- 无视频菜谱（recipe_id） ----
    @Select("SELECT * FROM video_reviews WHERE recipe_id = #{recipeId} ORDER BY created_at DESC")
    List<VideoReview> findByRecipeId(@Param("recipeId") Long recipeId);

    @Select("SELECT AVG(rating) FROM video_reviews WHERE recipe_id = #{recipeId}")
    Double avgRatingByRecipeId(@Param("recipeId") Long recipeId);

    @Select("SELECT COUNT(*) FROM video_reviews WHERE recipe_id = #{recipeId}")
    long countByRecipeId(@Param("recipeId") Long recipeId);
}
