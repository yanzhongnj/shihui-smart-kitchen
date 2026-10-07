package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.RecipeIngredient;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RecipeIngredientRepository extends BaseMapper<RecipeIngredient> {

    @Select("SELECT * FROM recipe_ingredients WHERE recipe_id = #{recipeId} ORDER BY seq ASC, id ASC")
    List<RecipeIngredient> findByRecipeId(@Param("recipeId") Long recipeId);

    @Delete("DELETE FROM recipe_ingredients WHERE recipe_id = #{recipeId}")
    int deleteByRecipeId(@Param("recipeId") Long recipeId);
}
