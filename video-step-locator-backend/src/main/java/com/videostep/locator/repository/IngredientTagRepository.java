package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.IngredientTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食材标签索引仓储（食材反查 P0）。
 * 重建时全量物理删除（本表无 deleted 逻辑删除列，防误触全局 logic-delete）。
 */
@Mapper
public interface IngredientTagRepository extends BaseMapper<IngredientTag> {
    @Delete("DELETE FROM ingredient_tags")
    int deleteAll();
}
