package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.ShoppingList;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShoppingListRepository extends BaseMapper<ShoppingList> {
    @Select("SELECT * FROM shopping_lists WHERE user_id = #{userId} ORDER BY id DESC")
    List<ShoppingList> findByUserId(@Param("userId") Long userId);
}
