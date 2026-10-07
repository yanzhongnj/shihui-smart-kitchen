package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.OrgMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrgMemberRepository extends BaseMapper<OrgMember> {
    @Select("SELECT * FROM org_members WHERE org_id = #{orgId} AND user_id = #{userId} AND status = 1 LIMIT 1")
    OrgMember findActiveByOrgAndUser(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
