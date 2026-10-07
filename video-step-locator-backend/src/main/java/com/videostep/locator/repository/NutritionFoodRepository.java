package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.NutritionFood;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食物营养库仓储。搜索/分类在 FoodLibraryService 用 LambdaQueryWrapper 实现。
 */
@Mapper
public interface NutritionFoodRepository extends BaseMapper<NutritionFood> {
}
