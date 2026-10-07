package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.RecipeSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RecipeSourceRepository extends BaseMapper<RecipeSource> {

    @Select("SELECT * FROM recipe_sources WHERE recipe_id = #{recipeId} ORDER BY id DESC")
    List<RecipeSource> findByRecipeId(@Param("recipeId") Long recipeId);
}
