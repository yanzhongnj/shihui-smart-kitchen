package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.DeviceSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceSkuRepository extends BaseMapper<DeviceSku> {
    @Select("SELECT * FROM device_skus WHERE id = #{id} AND status = 1 LIMIT 1")
    DeviceSku findEnabledById(@Param("id") Long id);
}
