package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.OrgSubscription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrgSubscriptionRepository extends BaseMapper<OrgSubscription> {
    @Select("SELECT * FROM org_subscriptions WHERE org_id = #{orgId} AND status = 'active' ORDER BY created_at DESC LIMIT 1")
    OrgSubscription findActiveByOrg(@Param("orgId") Long orgId);
}
