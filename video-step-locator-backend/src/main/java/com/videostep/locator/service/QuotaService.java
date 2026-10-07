package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.Org;
import com.videostep.locator.entity.OrgMember;
import com.videostep.locator.entity.ProcessingTask;
import com.videostep.locator.entity.Video;
import com.videostep.locator.repository.OrgMemberRepository;
import com.videostep.locator.repository.OrgRepository;
import com.videostep.locator.repository.ProcessingTaskRepository;
import com.videostep.locator.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * B 端配额检查：按企业 orgs.quota（JSON）限制视频数/成员数/活跃任务数等。
 * 超限抛 IllegalArgumentException（GlobalExceptionHandler 转 400 友好提示）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

    private final OrgRepository orgRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final VideoRepository videoRepository;
    private final ProcessingTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    /** 读取配额数值（JSON 缺省回退默认值） */
    @SuppressWarnings("unchecked")
    private long limit(Org org, String key, long def) {
        if (org.getQuota() == null || org.getQuota().isBlank()) return def;
        try {
            Map<String, Object> m = objectMapper.readValue(org.getQuota(), Map.class);
            Object v = m.get(key);
            return v == null ? def : Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            log.warn("parse quota failed org={} key={}", org.getId(), key, e);
            return def;
        }
    }

    /** 视频数量上限检查（上传/下载入库前）；欠费企业拒绝写操作 */
    public void checkVideoQuota(Long orgId) {
        Org org = requireOrg(orgId);
        requireActive(org);
        long limit = limit(org, "video_limit", 50);
        long current = videoRepository.selectCount(
                new LambdaQueryWrapper<Video>().eq(Video::getOrgId, orgId));
        if (current >= limit) {
            throw new IllegalArgumentException("企业视频配额已用尽（上限 " + limit + " 个），请升级套餐或联系管理员");
        }
    }

    /** 成员数量上限检查（邀请前）；欠费企业拒绝写操作 */
    public void checkMemberQuota(Long orgId) {
        Org org = requireOrg(orgId);
        requireActive(org);
        long limit = limit(org, "member_limit", 5);
        long current = orgMemberRepository.selectCount(
                new LambdaQueryWrapper<OrgMember>().eq(OrgMember::getOrgId, orgId).eq(OrgMember::getStatus, 1));
        if (current >= limit) {
            throw new IllegalArgumentException("企业成员数量已达上限（" + limit + " 人），请升级套餐");
        }
    }

    /** 活跃任务数量上限检查（发起处理前）；欠费企业拒绝写操作 */
    public void checkTaskQuota(Long orgId) {
        Org org = requireOrg(orgId);
        requireActive(org);
        long limit = limit(org, "task_limit", 100);
        long current = taskRepository.selectCount(new LambdaQueryWrapper<ProcessingTask>()
                .eq(ProcessingTask::getOrgId, orgId)
                .in(ProcessingTask::getStatus, ProcessingTask.STATUS_QUEUED, ProcessingTask.STATUS_RUNNING));
        if (current >= limit) {
            throw new IllegalArgumentException("企业并发处理任务已达上限（" + limit + "），请稍后再试或升级套餐");
        }
    }

    private Org requireOrg(Long orgId) {
        Org org = orgId == null ? null : orgRepository.selectById(orgId);
        if (org == null) throw new IllegalArgumentException("企业不存在");
        return org;
    }

    /** B3 欠费冻结：status=2（欠费停服）时拒绝写操作 */
    private void requireActive(Org org) {
        if (org.getStatus() != null && org.getStatus() == Org.STATUS_OVERDUE) {
            throw new IllegalArgumentException("企业已欠费停服，请续费后继续操作");
        }
    }
}
