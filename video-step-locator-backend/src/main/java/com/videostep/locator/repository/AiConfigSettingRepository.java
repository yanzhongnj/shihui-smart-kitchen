package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiConfigSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 配置系统级设置仓储（ai_config_settings）。
 */
@Mapper
public interface AiConfigSettingRepository extends BaseMapper<AiConfigSetting> {
}
