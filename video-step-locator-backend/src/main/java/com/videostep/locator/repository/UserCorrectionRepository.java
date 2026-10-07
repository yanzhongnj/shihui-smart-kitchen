package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.UserCorrection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserCorrectionRepository extends BaseMapper<UserCorrection> {

    @Select("SELECT * FROM user_corrections WHERE user_id = #{userId} "
            + "AND created_at >= #{start} AND created_at < #{end} ORDER BY id ASC")
    List<UserCorrection> findByUserAndDateRange(@Param("userId") Long userId,
                                                @Param("start") LocalDateTime start,
                                                @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM user_corrections WHERE user_id = #{userId} "
            + "AND created_at >= #{start} AND created_at < #{end}")
    long countByUserAndDateRange(@Param("userId") Long userId,
                                 @Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end);
}
