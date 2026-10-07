package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.UserExpLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserExpLogRepository extends BaseMapper<UserExpLog> {

    @Select("SELECT COALESCE(SUM(delta),0) FROM user_exp_logs WHERE user_id = #{userId}")
    long sumExp(@Param("userId") Long userId);

    @Select("SELECT * FROM user_exp_logs WHERE user_id = #{userId} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<UserExpLog> findRecent(@Param("userId") Long userId, @Param("limit") int limit);

    /** 统计某原因（如 correction/checkin/plan/work）在时间段内的条数（月度报告用） */
    @Select("SELECT COUNT(*) FROM user_exp_logs WHERE user_id = #{userId} AND reason = #{reason} "
            + "AND created_at >= #{start} AND created_at < #{end}")
    long countByReasonInRange(@Param("userId") Long userId, @Param("reason") String reason,
                              @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
