package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.CookingCheckin;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CookingCheckinRepository extends BaseMapper<CookingCheckin> {

    @Select("SELECT * FROM cooking_checkins WHERE user_id = #{userId} AND checkin_date >= #{start} AND checkin_date <= #{end} AND deleted = 0 ORDER BY checkin_date ASC, id ASC")
    List<CookingCheckin> findByUserAndDateRange(@Param("userId") Long userId,
                                                @Param("start") LocalDate start,
                                                @Param("end") LocalDate end);

    @Select("SELECT DISTINCT checkin_date FROM cooking_checkins WHERE user_id = #{userId} AND deleted = 0")
    List<LocalDate> findDistinctDates(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM cooking_checkins WHERE user_id = #{userId} AND deleted = 0")
    long countTotal(@Param("userId") Long userId);

    @Select("SELECT COUNT(DISTINCT recipe_title) FROM cooking_checkins WHERE user_id = #{userId} AND recipe_title IS NOT NULL AND recipe_title <> '' AND deleted = 0")
    long countDistinctRecipes(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM cooking_checkins WHERE user_id = #{userId} AND checkin_date = #{date} AND deleted = 0")
    long countByDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** 菜系分布（关联菜谱元数据） */
    @Select("SELECT rm.cuisine AS name, COUNT(*) AS cnt FROM cooking_checkins c "
            + "LEFT JOIN recipes rm ON c.video_id = rm.video_id "
            + "WHERE c.user_id = #{userId} AND c.checkin_date >= #{start} AND c.checkin_date <= #{end} "
            + "AND c.deleted = 0 AND rm.cuisine IS NOT NULL AND rm.cuisine <> '' "
            + "GROUP BY rm.cuisine ORDER BY cnt DESC LIMIT #{limit}")
    List<Map<String, Object>> countCuisines(@Param("userId") Long userId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end,
                                            @Param("limit") int limit);

    // ---------------- 回收站（软删除记录治理，仅 ADMIN 调用） ----------------

    /** 软删除并记录删除时间（MP deleteById 只置 deleted=1，不写 deleted_at） */
    @Update("UPDATE cooking_checkins SET deleted = 1, deleted_at = NOW() WHERE id = #{id} AND deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /** 恢复（清除删除标记与删除时间） */
    @Update("UPDATE cooking_checkins SET deleted = 0, deleted_at = NULL WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 按 ID 查已删行（selectById 会被 MP 自动过滤，查不到已删行） */
    @Select("SELECT * FROM cooking_checkins WHERE id = #{id} AND deleted = 1")
    CookingCheckin findDeletedById(@Param("id") Long id);

    /** 已删记录分页（含用户名），回收站列表 */
    @Select("<script>SELECT c.id, c.user_id, c.checkin_date, c.recipe_title, c.meal_type, c.mood, c.note, c.calories, c.source, c.created_at, c.deleted_at, u.username "
            + "FROM cooking_checkins c LEFT JOIN users u ON u.id = c.user_id WHERE c.deleted = 1 "
            + "<if test='userId != null'>AND c.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (c.recipe_title LIKE CONCAT('%', #{keyword}, '%') OR c.note LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "ORDER BY c.deleted_at DESC, c.id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findDeletedPage(@Param("userId") Long userId, @Param("keyword") String keyword,
                                              @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM cooking_checkins c WHERE c.deleted = 1 "
            + "<if test='userId != null'>AND c.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (c.recipe_title LIKE CONCAT('%', #{keyword}, '%') OR c.note LIKE CONCAT('%', #{keyword}, '%')) </if></script>")
    long countDeleted(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 彻底删除（物理删，须先归档；原生 DELETE 绕过 MP 逻辑删除） */
    @Delete("DELETE FROM cooking_checkins WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);

    /** 归档（彻底删除前把原行 INSERT…SELECT 进归档表，archived_by=操作管理员） */
    @Insert("INSERT INTO cooking_checkins_archive (original_id, user_id, checkin_date, video_id, recipe_title, meal_type, mood, note, calories, source, created_at, deleted_at, archived_at, archived_by) "
            + "SELECT id, user_id, checkin_date, video_id, recipe_title, meal_type, mood, note, calories, source, created_at, deleted_at, NOW(), #{archivedBy} "
            + "FROM cooking_checkins WHERE id = #{id} AND deleted = 1")
    int archiveRow(@Param("id") Long id, @Param("archivedBy") Long archivedBy);

    /** 超期已删记录 ID 分页（供超期自动清理任务） */
    @Select("SELECT id FROM cooking_checkins WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{before} ORDER BY id ASC LIMIT #{offset}, #{size}")
    List<Long> findExpiredIds(@Param("before") LocalDateTime before, @Param("offset") int offset, @Param("size") int size);
}
