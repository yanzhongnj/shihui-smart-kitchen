package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.DeviceBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceBindingRepository extends BaseMapper<DeviceBinding> {
    @Select("SELECT * FROM device_bindings WHERE bind_code = #{bindCode} LIMIT 1")
    DeviceBinding findByCode(@Param("bindCode") String bindCode);

    @Select("SELECT COUNT(*) FROM device_bindings WHERE vendor_org_id = #{orgId} AND status = 'bound'")
    long countBoundByOrg(@Param("orgId") Long orgId);
}
