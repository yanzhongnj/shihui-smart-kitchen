package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.VendorContract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VendorContractRepository extends BaseMapper<VendorContract> {
    @Select("SELECT * FROM vendor_contracts WHERE org_id = #{orgId} AND status = 'active' ORDER BY id DESC LIMIT 1")
    VendorContract findActiveByOrg(@Param("orgId") Long orgId);
}
