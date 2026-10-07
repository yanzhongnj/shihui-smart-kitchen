package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.CookingPlan;
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
public interface CookingPlanRepository extends BaseMapper<CookingPlan> {

    @Select("SELECT * FROM cooking_plans WHERE user_id = #{userId} AND deleted = 0 ORDER BY start_date DESC, id DESC")
    List<CookingPlan> findByUserId(@Param("userId") Long userId);

    // ---------------- 回收站（软删除记录治理，仅 ADMIN 调用） ----------------

    /** 软删除并记录删除时间 */
    @Update("UPDATE cooking_plans SET deleted = 1, deleted_at = NOW() WHERE id = #{id} AND deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /** 恢复 */
    @Update("UPDATE cooking_plans SET deleted = 0, deleted_at = NULL WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 按 ID 查已删行 */
    @Select("SELECT * FROM cooking_plans WHERE id = #{id} AND deleted = 1")
    CookingPlan findDeletedById(@Param("id") Long id);

    /** 已删记录分页（含用户名） */
    @Select("<script>SELECT p.id, p.user_id, p.title, p.plan_type, p.start_date, p.created_at, p.deleted_at, u.username "
            + "FROM cooking_plans p LEFT JOIN users u ON u.id = p.user_id WHERE p.deleted = 1 "
            + "<if test='userId != null'>AND p.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND p.title LIKE CONCAT('%', #{keyword}, '%') </if>"
            + "ORDER BY p.deleted_at DESC, p.id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findDeletedPage(@Param("userId") Long userId, @Param("keyword") String keyword,
                                              @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM cooking_plans p WHERE p.deleted = 1 "
            + "<if test='userId != null'>AND p.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND p.title LIKE CONCAT('%', #{keyword}, '%') </if></script>")
    long countDeleted(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 彻底删除（物理删，须先归档；计划物理删触发 cooking_plan_items FK ON DELETE CASCADE） */
    @Delete("DELETE FROM cooking_plans WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);

    /** 归档（INSERT…SELECT 进归档表） */
    @Insert("INSERT INTO cooking_plans_archive (original_id, user_id, title, plan_type, start_date, created_at, deleted_at, archived_at, archived_by) "
            + "SELECT id, user_id, title, plan_type, start_date, created_at, deleted_at, NOW(), #{archivedBy} "
            + "FROM cooking_plans WHERE id = #{id} AND deleted = 1")
    int archiveRow(@Param("id") Long id, @Param("archivedBy") Long archivedBy);

    /** 超期已删记录 ID 分页（供超期自动清理任务） */
    @Select("SELECT id FROM cooking_plans WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{before} ORDER BY id ASC LIMIT #{offset}, #{size}")
    List<Long> findExpiredIds(@Param("before") LocalDateTime before, @Param("offset") int offset, @Param("size") int size);
}
