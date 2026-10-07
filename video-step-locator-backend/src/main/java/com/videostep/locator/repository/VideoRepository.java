package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.Video;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import java.util.Optional;

@Mapper
public interface VideoRepository extends BaseMapper<Video> {
    @Select("SELECT * FROM videos WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<Video> findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM videos WHERE status = #{status} ORDER BY created_at ASC")
    List<Video> findByStatus(@Param("status") String status);

    @Select("SELECT * FROM videos WHERE md5_hash = #{md5} AND user_id = #{userId}")
    Optional<Video> findByMd5AndUserId(@Param("md5") String md5, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM videos WHERE user_id = #{userId} AND status = #{status}")
    long countByUserIdAndStatus(@Param("userId") Long userId, @Param("status") String status);

    @Update("UPDATE videos SET status = #{status} WHERE id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    @Select("SELECT COALESCE(SUM(file_size),0) FROM videos WHERE org_id = #{orgId}")
    long sumFileSizeByOrg(@Param("orgId") Long orgId);

    @Select("SELECT COUNT(*) FROM videos WHERE org_id = #{orgId}")
    long countByOrg(@Param("orgId") Long orgId);
}