package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.DeviceCommandLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceCommandLogRepository extends BaseMapper<DeviceCommandLog> {
}
