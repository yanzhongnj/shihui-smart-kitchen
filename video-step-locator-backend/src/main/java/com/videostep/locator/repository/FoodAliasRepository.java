package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.FoodAlias;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 食材同义词仓储（反查归一 + 营养计算匹配共用）。
 */
@Mapper
public interface FoodAliasRepository extends BaseMapper<FoodAlias> {
    @Select("SELECT * FROM food_aliases WHERE alias = #{alias} LIMIT 1")
    FoodAlias findByAlias(@Param("alias") String alias);
}
