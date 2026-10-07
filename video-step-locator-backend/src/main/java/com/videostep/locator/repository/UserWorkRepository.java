package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.UserWork;
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
public interface UserWorkRepository extends BaseMapper<UserWork> {

    @Select("SELECT * FROM user_works WHERE user_id = #{userId} AND deleted = 0 ORDER BY created_at DESC, id DESC")
    List<UserWork> findByUserId(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM user_works WHERE user_id = #{userId} AND created_at >= #{start} AND created_at < #{end} AND deleted = 0")
    long countInRange(@Param("userId") Long userId,
                      @Param("start") java.time.LocalDateTime start,
                      @Param("end") java.time.LocalDateTime end);

    // ---------------- 回收站（软删除记录治理，仅 ADMIN 调用） ----------------

    /** 软删除并记录删除时间 */
    @Update("UPDATE user_works SET deleted = 1, deleted_at = NOW() WHERE id = #{id} AND deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /** 恢复 */
    @Update("UPDATE user_works SET deleted = 0, deleted_at = NULL WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 按 ID 查已删行 */
    @Select("SELECT * FROM user_works WHERE id = #{id} AND deleted = 1")
    UserWork findDeletedById(@Param("id") Long id);

    /** 已删记录分页（含用户名） */
    @Select("<script>SELECT w.id, w.user_id, w.video_id, w.recipe_title, w.title, w.description, w.images, w.status, w.created_at, w.deleted_at, u.username "
            + "FROM user_works w LEFT JOIN users u ON u.id = w.user_id WHERE w.deleted = 1 "
            + "<if test='userId != null'>AND w.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (w.title LIKE CONCAT('%', #{keyword}, '%') OR w.recipe_title LIKE CONCAT('%', #{keyword}, '%') OR w.description LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "ORDER BY w.deleted_at DESC, w.id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findDeletedPage(@Param("userId") Long userId, @Param("keyword") String keyword,
                                              @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM user_works w WHERE w.deleted = 1 "
            + "<if test='userId != null'>AND w.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (w.title LIKE CONCAT('%', #{keyword}, '%') OR w.recipe_title LIKE CONCAT('%', #{keyword}, '%') OR w.description LIKE CONCAT('%', #{keyword}, '%')) </if></script>")
    long countDeleted(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 创建失败回滚：物理删除刚插入的行（未进入软删流程，deleted=0） */
    @Delete("DELETE FROM user_works WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /** 彻底删除（物理删，须先归档；磁盘图片由 FileStorageService.deleteWorkImages 另行处理） */
    @Delete("DELETE FROM user_works WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);

    /** 归档（INSERT…SELECT 进归档表，保留 images 路径） */
    @Insert("INSERT INTO user_works_archive (original_id, user_id, video_id, recipe_title, title, description, images, status, created_at, deleted_at, archived_at, archived_by) "
            + "SELECT id, user_id, video_id, recipe_title, title, description, images, status, created_at, deleted_at, NOW(), #{archivedBy} "
            + "FROM user_works WHERE id = #{id} AND deleted = 1")
    int archiveRow(@Param("id") Long id, @Param("archivedBy") Long archivedBy);

    /** 超期已删记录 ID 分页（供超期自动清理任务） */
    @Select("SELECT id FROM user_works WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{before} ORDER BY id ASC LIMIT #{offset}, #{size}")
    List<Long> findExpiredIds(@Param("before") LocalDateTime before, @Param("offset") int offset, @Param("size") int size);
}
