package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.ContentLicense;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ContentLicenseRepository extends BaseMapper<ContentLicense> {
    @Select("SELECT * FROM content_licenses WHERE vendor_org_id = #{orgId} AND recipe_group = #{group} LIMIT 1")
    ContentLicense findByOrgAndGroup(@Param("orgId") Long orgId, @Param("group") String group);

    @Select("SELECT * FROM content_licenses WHERE vendor_org_id = #{orgId} AND creation_id = #{creationId} LIMIT 1")
    ContentLicense findByOrgAndCreation(@Param("orgId") Long orgId, @Param("creationId") Long creationId);
}
