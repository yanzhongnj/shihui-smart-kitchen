package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.UsageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UsageRecordRepository extends BaseMapper<UsageRecord> {
    @Select("SELECT COALESCE(SUM(bytes),0) FROM usage_records WHERE device_id = #{deviceId} AND action_type = #{actionType}")
    long sumBytesByDeviceAndType(@Param("deviceId") String deviceId, @Param("actionType") String actionType);

    @Select("SELECT COUNT(*) FROM usage_records WHERE device_id = #{deviceId} AND action_type = #{actionType}")
    long countByDeviceAndType(@Param("deviceId") String deviceId, @Param("actionType") String actionType);

    @Select("SELECT COALESCE(SUM(bytes),0) FROM usage_records WHERE user_id = #{userId}")
    long sumBytesByUser(@Param("userId") Long userId);

    @Select("SELECT COALESCE(SUM(bytes),0) FROM usage_records WHERE user_id = #{userId} "
            + "AND action_type = #{actionType} AND created_at >= CURDATE()")
    long sumByUserAndTypeToday(@Param("userId") Long userId, @Param("actionType") String actionType);

    @Select("SELECT COUNT(*) FROM usage_records WHERE user_id = #{userId} "
            + "AND action_type = #{actionType} AND created_at >= CURDATE()")
    long countByUserAndTypeToday(@Param("userId") Long userId, @Param("actionType") String actionType);

    @Select("SELECT COALESCE(SUM(bytes),0) FROM usage_records WHERE user_id IN "
            + "(SELECT user_id FROM org_members WHERE org_id = #{orgId} AND status = 1)")
    long sumBytesByOrg(@Param("orgId") Long orgId);
}
