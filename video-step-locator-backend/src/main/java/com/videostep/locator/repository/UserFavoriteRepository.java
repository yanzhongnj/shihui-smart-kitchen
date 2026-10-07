package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.UserFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface UserFavoriteRepository extends BaseMapper<UserFavorite> {
    @Select("SELECT * FROM user_favorites WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<UserFavorite> findByUserId(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM user_favorites WHERE video_id = #{videoId}")
    long countByVideoId(@Param("videoId") Long videoId);

    @Select("SELECT COUNT(*) FROM user_favorites WHERE recipe_id = #{recipeId}")
    long countByRecipeId(@Param("recipeId") Long recipeId);
}
