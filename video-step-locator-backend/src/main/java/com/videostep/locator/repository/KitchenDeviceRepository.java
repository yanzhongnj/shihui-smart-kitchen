package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.KitchenDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KitchenDeviceRepository extends BaseMapper<KitchenDevice> {
    @Select("SELECT * FROM kitchen_devices WHERE vendor = #{vendor} AND vendor_device_id = #{vendorDeviceId} LIMIT 1")
    KitchenDevice findByVendorAndVendorDeviceId(@Param("vendor") String vendor, @Param("vendorDeviceId") String vendorDeviceId);

    /** 按设备类型找在线设备（企业成员用 org_id；个人用 user_id） */
    @Select("SELECT * FROM kitchen_devices WHERE (org_id = #{orgId} OR (org_id IS NULL AND user_id = #{userId})) "
            + "AND device_type = #{deviceType} AND online = 1 ORDER BY id DESC LIMIT 1")
    KitchenDevice findOnlineByType(@Param("orgId") Long orgId,
                                   @Param("userId") Long userId,
                                   @Param("deviceType") String deviceType);
}
