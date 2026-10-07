package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.CookingMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CookingMenuRepository extends BaseMapper<CookingMenu> {
    @Select("SELECT * FROM cooking_menus WHERE user_id = #{userId} AND menu_week = #{week} ORDER BY id DESC")
    List<CookingMenu> findByUserAndWeek(@Param("userId") Long userId, @Param("week") LocalDate week);
}
