package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.ShareLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ShareLinkRepository extends BaseMapper<ShareLink> {
    @Select("SELECT * FROM share_links WHERE code = #{code} LIMIT 1")
    ShareLink findByCode(@Param("code") String code);

    @Select("SELECT * FROM share_links WHERE created_by = #{userId} ORDER BY created_at DESC")
    List<ShareLink> findByCreatedBy(@Param("userId") Long userId);

    /** 未过期分享链接数（用于「已共享」锁定判定） */
    @Select("SELECT COUNT(*) FROM share_links WHERE video_id = #{videoId} AND (expires_at IS NULL OR expires_at > NOW())")
    long countActiveByVideoId(@Param("videoId") Long videoId);
}
