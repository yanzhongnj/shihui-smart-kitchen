package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.RecycleSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回收站超期清理配置仓储（单行 id=1）。
 */
@Mapper
public interface RecycleSettingRepository extends BaseMapper<RecycleSetting> {
}
