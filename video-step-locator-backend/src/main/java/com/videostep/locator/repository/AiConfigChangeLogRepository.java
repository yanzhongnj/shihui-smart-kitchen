package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiConfigChangeLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 配置变更日志仓储（ai_config_change_logs）。
 */
@Mapper
public interface AiConfigChangeLogRepository extends BaseMapper<AiConfigChangeLog> {
}
