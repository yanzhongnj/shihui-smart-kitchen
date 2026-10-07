package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.ApiUsageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApiUsageRecordRepository extends BaseMapper<ApiUsageRecord> {
    @Select("SELECT COUNT(*) FROM api_usage_records WHERE client_id = #{clientId} AND created_at >= DATE_SUB(NOW(), INTERVAL 1 MINUTE)")
    long countLastMinute(@Param("clientId") Long clientId);
}
