package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.XunweiMemory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface XunweiMemoryRepository extends BaseMapper<XunweiMemory> {
    @Select("SELECT * FROM xunwei_memory WHERE user_id = #{userId}")
    List<XunweiMemory> findByUserId(Long userId);

    @Select("SELECT * FROM xunwei_memory WHERE user_id = #{userId} AND deleted = 0 ORDER BY updated_at DESC")
    List<XunweiMemory> findActive(Long userId);

    /** 含软删除行的查询（供重复抽取抑制判断；MP 逻辑删除会让 selectOne 自动过滤 deleted=1） */
    @Select("SELECT * FROM xunwei_memory WHERE user_id = #{userId} AND mem_key = #{key} LIMIT 1")
    XunweiMemory findOneByUserAndKey(Long userId, String key);

    /** 全局有效画像分页（供记忆衰减任务批量处理） */
    @Select("SELECT * FROM xunwei_memory WHERE deleted = 0 ORDER BY id ASC LIMIT #{offset}, #{limit}")
    List<XunweiMemory> findActiveGlobal(@Param("offset") int offset, @Param("limit") int limit);

    /** 软删除并记录删除时间（MP deleteById 只置 deleted=1，不写 deleted_at） */
    @Update("UPDATE xunwei_memory SET deleted = 1, deleted_at = NOW() WHERE id = #{id} AND deleted = 0")
    int softDeleteById(@Param("id") Long id);

    // ---------------- 回收站（软删除记录治理，仅 ADMIN 调用） ----------------

    /** 恢复（清除删除标记与删除时间） */
    @Update("UPDATE xunwei_memory SET deleted = 0, deleted_at = NULL WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 按 ID 查已删行 */
    @Select("SELECT * FROM xunwei_memory WHERE id = #{id} AND deleted = 1")
    XunweiMemory findDeletedById(@Param("id") Long id);

    /** 已删记录分页（含用户名） */
    @Select("<script>SELECT m.id, m.user_id, m.mem_key, m.mem_value, m.mem_type, m.confidence, m.source, m.ref_id, m.hit_count, m.updated_at, m.deleted_at, u.username "
            + "FROM xunwei_memory m LEFT JOIN users u ON u.id = m.user_id WHERE m.deleted = 1 "
            + "<if test='userId != null'>AND m.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (m.mem_key LIKE CONCAT('%', #{keyword}, '%') OR m.mem_value LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "ORDER BY m.deleted_at DESC, m.id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findDeletedPage(@Param("userId") Long userId, @Param("keyword") String keyword,
                                              @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM xunwei_memory m WHERE m.deleted = 1 "
            + "<if test='userId != null'>AND m.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (m.mem_key LIKE CONCAT('%', #{keyword}, '%') OR m.mem_value LIKE CONCAT('%', #{keyword}, '%')) </if></script>")
    long countDeleted(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 彻底删除（物理删，须先归档） */
    @Delete("DELETE FROM xunwei_memory WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);

    /** 归档（INSERT…SELECT 进归档表） */
    @Insert("INSERT INTO xunwei_memory_archive (original_id, user_id, mem_key, mem_value, mem_type, confidence, source, ref_id, hit_count, updated_at, deleted_at, archived_at, archived_by) "
            + "SELECT id, user_id, mem_key, mem_value, mem_type, confidence, source, ref_id, hit_count, updated_at, deleted_at, NOW(), #{archivedBy} "
            + "FROM xunwei_memory WHERE id = #{id} AND deleted = 1")
    int archiveRow(@Param("id") Long id, @Param("archivedBy") Long archivedBy);

    /** 超期已删记录 ID 分页（供超期自动清理任务） */
    @Select("SELECT id FROM xunwei_memory WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{before} ORDER BY id ASC LIMIT #{offset}, #{size}")
    List<Long> findExpiredIds(@Param("before") LocalDateTime before, @Param("offset") int offset, @Param("size") int size);

    // ---------------- 管理端数据画像（含软删筛选；MP selectList 会自动加 deleted=0，须原生 SQL） ----------------

    @Select("<script>SELECT * FROM xunwei_memory WHERE 1=1 "
            + "<if test='userId != null'>AND user_id = #{userId} </if>"
            + "<if test='memKey != null and memKey != \"\"'>AND mem_key = #{memKey} </if>"
            + "<if test='memType != null and memType != \"\"'>AND mem_type = #{memType} </if>"
            + "<if test='source != null and source != \"\"'>AND source = #{source} </if>"
            + "<if test='deleted != null'>AND deleted = #{deleted} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND mem_value LIKE CONCAT('%', #{keyword}, '%') </if>"
            + "ORDER BY updated_at DESC LIMIT #{offset}, #{size}</script>")
    List<XunweiMemory> findPageFiltered(@Param("userId") Long userId, @Param("memKey") String memKey,
                                        @Param("memType") String memType, @Param("source") String source,
                                        @Param("deleted") Integer deleted, @Param("keyword") String keyword,
                                        @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM xunwei_memory WHERE 1=1 "
            + "<if test='userId != null'>AND user_id = #{userId} </if>"
            + "<if test='memKey != null and memKey != \"\"'>AND mem_key = #{memKey} </if>"
            + "<if test='memType != null and memType != \"\"'>AND mem_type = #{memType} </if>"
            + "<if test='source != null and source != \"\"'>AND source = #{source} </if>"
            + "<if test='deleted != null'>AND deleted = #{deleted} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND mem_value LIKE CONCAT('%', #{keyword}, '%') </if></script>")
    long countFiltered(@Param("userId") Long userId, @Param("memKey") String memKey,
                       @Param("memType") String memType, @Param("source") String source,
                       @Param("deleted") Integer deleted, @Param("keyword") String keyword);
}
