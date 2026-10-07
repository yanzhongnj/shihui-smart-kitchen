package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AiCreationVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiCreationVersionRepository extends BaseMapper<AiCreationVersion> {

    @Select("SELECT * FROM ai_creation_versions WHERE creation_id = #{creationId} ORDER BY version ASC")
    List<AiCreationVersion> findByCreationId(@Param("creationId") Long creationId);

    @Select("SELECT MAX(version) FROM ai_creation_versions WHERE creation_id = #{creationId}")
    Integer maxVersion(@Param("creationId") Long creationId);
}
