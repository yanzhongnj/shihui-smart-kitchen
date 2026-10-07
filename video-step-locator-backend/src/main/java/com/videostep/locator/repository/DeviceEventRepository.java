package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.DeviceEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceEventRepository extends BaseMapper<DeviceEvent> {
}
