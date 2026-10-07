package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.WeightLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface WeightLogRepository extends BaseMapper<WeightLog> {

    @Select("SELECT * FROM weight_logs WHERE user_id = #{userId} AND log_date >= #{start} ORDER BY log_date ASC")
    List<WeightLog> findSince(@Param("userId") Long userId, @Param("start") LocalDate start);

    @Select("SELECT * FROM weight_logs WHERE user_id = #{userId} ORDER BY log_date DESC LIMIT 1")
    WeightLog findLatest(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM weight_logs WHERE user_id = #{userId}")
    long countTotal(@Param("userId") Long userId);
}
