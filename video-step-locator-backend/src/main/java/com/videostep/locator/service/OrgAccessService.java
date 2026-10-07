package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.Org;
import com.videostep.locator.entity.OrgMember;
import com.videostep.locator.repository.OrgMemberRepository;
import com.videostep.locator.repository.OrgRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * B 端组织访问助手：统一解析用户所属企业、校验成员/角色权限。
 * 设计约定（见 docs/拾味_面向B端_技术设计文档.md）：
 * - 数据隔离用 org_id 手工过滤，不引入 MyBatis-Plus 多租户插件；
 * - 个人用户 org_id = null，走原个人逻辑。
 */
@Service
@RequiredArgsConstructor
public class OrgAccessService {

    private final OrgRepository orgRepository;
    private final OrgMemberRepository orgMemberRepository;

    /** 用户所属在职组织（无则 null） */
    public Org getOrgOf(Long userId) {
        if (userId == null) return null;
        List<OrgMember> members = orgMemberRepository.selectList(
                new LambdaQueryWrapper<OrgMember>()
                        .eq(OrgMember::getUserId, userId)
                        .eq(OrgMember::getStatus, 1)
                        .orderByDesc(OrgMember::getId));
        if (members.isEmpty()) return null;
        return orgRepository.selectById(members.get(0).getOrgId());
    }

    /** 用户所属 orgId（无则 null） */
    public Long getOrgIdOf(Long userId) {
        Org org = getOrgOf(userId);
        return org == null ? null : org.getId();
    }

    /** 有效 orgId：个人用户返回 null（走个人逻辑） */
    public Long effectiveOrgId(Long userId) {
        return getOrgIdOf(userId);
    }

    /** 是否组织在职成员 */
    public boolean isMember(Long userId, Long orgId) {
        if (userId == null || orgId == null) return false;
        return orgMemberRepository.findActiveByOrgAndUser(orgId, userId) != null;
    }

    /** 非成员抛 403 语义异常 */
    public void requireMember(Long userId, Long orgId) {
        if (!isMember(userId, orgId)) {
            throw new SecurityException("您不是该企业成员，无权操作");
        }
    }

    /** 是否具备指定角色（owner 拥有一切权限） */
    public boolean hasRole(Long userId, Long orgId, String role) {
        OrgMember m = orgMemberRepository.findActiveByOrgAndUser(orgId, userId);
        if (m == null) return false;
        if (OrgMember.ROLE_OWNER.equals(m.getRole())) return true;
        return m.getRole().equals(role);
    }

    /** 不具备指定角色则抛异常 */
    public void requireRole(Long userId, Long orgId, String role) {
        if (!hasRole(userId, orgId, role)) {
            throw new SecurityException("无权执行该操作");
        }
    }

    /** 是否 owner/admin（企业管理类操作） */
    public boolean isOwnerOrAdmin(Long userId, Long orgId) {
        return hasRole(userId, orgId, OrgMember.ROLE_ADMIN);
    }

    /** 用户在企业中的角色（非成员返回 null） */
    public String getUserRole(Long userId, Long orgId) {
        OrgMember m = orgMemberRepository.findActiveByOrgAndUser(orgId, userId);
        return m == null ? null : m.getRole();
    }
}
