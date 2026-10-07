package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.RecipeMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecipeMetadataRepository extends BaseMapper<RecipeMetadata> {
    @Select("SELECT * FROM recipes WHERE video_id = #{videoId} LIMIT 1")
    RecipeMetadata findByVideoId(@Param("videoId") Long videoId);

    @Select("SELECT * FROM recipes WHERE creation_id = #{creationId} LIMIT 1")
    RecipeMetadata findByCreationId(@Param("creationId") Long creationId);

    @Select("SELECT * FROM recipes WHERE source_url = #{sourceUrl} LIMIT 1")
    RecipeMetadata findBySourceUrl(@Param("sourceUrl") String sourceUrl);

    @Select("SELECT COUNT(*) FROM recipes WHERE org_id = #{orgId}")
    long countByOrg(@Param("orgId") Long orgId);
}
