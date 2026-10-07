package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiProviderConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 引擎配置仓储（ai_provider_configs）。
 */
@Mapper
public interface AiProviderConfigRepository extends BaseMapper<AiProviderConfig> {
}
