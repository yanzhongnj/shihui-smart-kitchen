package com.videostep.locator.service.creation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiCreationVersion;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.repository.AiCreationVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 酿味菜谱版本快照 / 回退（v0.3 迭代护栏支撑）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationRevisionService {

    private final AiCreationVersionRepository versionRepository;
    private final AiCreationRepository creationRepository;

    /** 为当前版本创建快照（version 1..n） */
    public void snapshot(Long creationId, int version, String stepsJson, String deltaNote) {
        AiCreationVersion v = AiCreationVersion.builder()
                .creationId(creationId)
                .version(version)
                .stepsJson(stepsJson)
                .deltaNote(deltaNote)
                .build();
        versionRepository.insert(v);
    }

    public List<AiCreationVersion> listVersions(Long creationId) {
        return versionRepository.findByCreationId(creationId);
    }

    /**
     * 回退到指定版本：先把当前 steps 快照为新版本（保留现场），再恢复目标版本。
     */
    public AiCreation revert(Long userId, AiCreation creation, int targetVersion) {
        AiCreationVersion target = versionRepository.selectOne(new LambdaQueryWrapper<AiCreationVersion>()
                .eq(AiCreationVersion::getCreationId, creation.getId())
                .eq(AiCreationVersion::getVersion, targetVersion));
        if (target == null) {
            throw new IllegalArgumentException("版本不存在");
        }
        int currentVersion = creation.getVersion() == null ? 0 : creation.getVersion();
        int newVersion = currentVersion + 1;
        // 快照当前现场（保留历史）
        snapshot(creation.getId(), newVersion, creation.getSteps(), "回退至 v" + targetVersion);
        creation.setSteps(target.getStepsJson());
        creation.setVersion(newVersion);
        creationRepository.updateById(creation);
        log.info("[酿味] 回退创作 creationId={} -> v{}，当前 v{}", creation.getId(), targetVersion, newVersion);
        return creation;
    }
}
