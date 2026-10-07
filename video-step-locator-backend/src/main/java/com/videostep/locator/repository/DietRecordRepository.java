package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.DietRecord;
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
public interface DietRecordRepository extends BaseMapper<DietRecord> {

    @Select("SELECT * FROM diet_records WHERE user_id = #{userId} AND record_date = #{date} AND deleted = 0 ORDER BY id ASC")
    List<DietRecord> findByUserAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** 跨来源去重：同用户同日同菜谱（recipe_id）已存在的饮食记录（v0.3 决策 7.7-4） */
    @Select("SELECT id FROM diet_records WHERE user_id = #{userId} AND record_date = #{date} AND recipe_id = #{recipeId} AND deleted = 0 ORDER BY id ASC LIMIT 1")
    Long findIdByUserDateRecipe(@Param("userId") Long userId, @Param("date") LocalDate date, @Param("recipeId") Long recipeId);

    @Select("SELECT * FROM diet_records WHERE user_id = #{userId} AND record_date >= #{start} AND record_date <= #{end} AND deleted = 0 ORDER BY record_date DESC, id DESC")
    List<DietRecord> findByUserAndDateRange(@Param("userId") Long userId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);

    @Select("SELECT * FROM diet_records WHERE checkin_id = #{checkinId} AND deleted = 0")
    List<DietRecord> findByCheckinId(@Param("checkinId") Long checkinId);

    /** 删除打卡时级联软删除其自动生成的饮食记录（含删除时间） */
    @Update("UPDATE diet_records SET deleted = 1, deleted_at = NOW() WHERE user_id = #{userId} AND checkin_id = #{checkinId} AND deleted = 0")
    int deleteByCheckinId(@Param("userId") Long userId, @Param("checkinId") Long checkinId);

    /** 打卡恢复时级联恢复其自动生成的饮食记录 */
    @Update("UPDATE diet_records SET deleted = 0, deleted_at = NULL WHERE user_id = #{userId} AND checkin_id = #{checkinId} AND deleted = 1")
    int restoreByCheckinId(@Param("userId") Long userId, @Param("checkinId") Long checkinId);

    /** 打卡彻底删除时物理清理其软删饮食行（打卡已物理删，防孤儿行） */
    @Delete("DELETE FROM diet_records WHERE checkin_id = #{checkinId} AND deleted = 1")
    int purgeByCheckinId(@Param("checkinId") Long checkinId);

    /** 按 photo_id 查未删饮食行（拍照打卡确认时复用/更新） */
    @Select("SELECT * FROM diet_records WHERE photo_id = #{photoId} AND deleted = 0 LIMIT 1")
    DietRecord findByPhotoId(@Param("photoId") Long photoId);

    /** 删除拍照来源的饮食时级联软删除其 meal_photos（scan 删除级联，对齐删打卡级联删 diet） */
    @Update("UPDATE diet_records SET deleted = 1, deleted_at = NOW() WHERE photo_id = #{photoId} AND deleted = 0")
    int softDeleteByPhotoId(@Param("photoId") Long photoId);

    /** 拍照打卡恢复时级联恢复其软删饮食行 */
    @Update("UPDATE diet_records SET deleted = 0, deleted_at = NULL WHERE photo_id = #{photoId} AND deleted = 1")
    int restoreByPhotoId(@Param("photoId") Long photoId);

    /** 拍照打卡彻底删除时物理清理其软删饮食行 */
    @Delete("DELETE FROM diet_records WHERE photo_id = #{photoId} AND deleted = 1")
    int purgeByPhotoId(@Param("photoId") Long photoId);

    @Select("SELECT COUNT(*) FROM diet_records WHERE user_id = #{userId} AND record_date >= #{start} AND record_date <= #{end} AND deleted = 0")
    long countInRange(@Param("userId") Long userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    // ---------------- 回收站（软删除记录治理，仅 ADMIN 调用） ----------------

    /** 软删除并记录删除时间 */
    @Update("UPDATE diet_records SET deleted = 1, deleted_at = NOW() WHERE id = #{id} AND deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /** 恢复 */
    @Update("UPDATE diet_records SET deleted = 0, deleted_at = NULL WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 按 ID 查已删行 */
    @Select("SELECT * FROM diet_records WHERE id = #{id} AND deleted = 1")
    DietRecord findDeletedById(@Param("id") Long id);

    /** 已删记录分页（含用户名） */
    @Select("<script>SELECT d.id, d.user_id, d.record_date, d.meal_type, d.recipe_id, d.checkin_id, d.calories, d.estimated, d.source, d.note, d.created_at, d.deleted_at, u.username "
            + "FROM diet_records d LEFT JOIN users u ON u.id = d.user_id WHERE d.deleted = 1 "
            + "<if test='userId != null'>AND d.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (d.note LIKE CONCAT('%', #{keyword}, '%') OR CAST(d.items AS CHAR) LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "ORDER BY d.deleted_at DESC, d.id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findDeletedPage(@Param("userId") Long userId, @Param("keyword") String keyword,
                                              @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM diet_records d WHERE d.deleted = 1 "
            + "<if test='userId != null'>AND d.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (d.note LIKE CONCAT('%', #{keyword}, '%') OR CAST(d.items AS CHAR) LIKE CONCAT('%', #{keyword}, '%')) </if></script>")
    long countDeleted(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 彻底删除（物理删，须先归档） */
    @Delete("DELETE FROM diet_records WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);

    /** 归档（INSERT…SELECT 进归档表） */
    @Insert("INSERT INTO diet_records_archive (original_id, user_id, record_date, meal_type, recipe_id, checkin_id, items, calories, protein, fat, carbs, portion_factor, estimated, source, note, photo_id, created_at, deleted_at, archived_at, archived_by) "
            + "SELECT id, user_id, record_date, meal_type, recipe_id, checkin_id, items, calories, protein, fat, carbs, portion_factor, estimated, source, note, photo_id, created_at, deleted_at, NOW(), #{archivedBy} "
            + "FROM diet_records WHERE id = #{id} AND deleted = 1")
    int archiveRow(@Param("id") Long id, @Param("archivedBy") Long archivedBy);

    /** 超期已删记录 ID 分页（供超期自动清理任务） */
    @Select("SELECT id FROM diet_records WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{before} ORDER BY id ASC LIMIT #{offset}, #{size}")
    List<Long> findExpiredIds(@Param("before") LocalDateTime before, @Param("offset") int offset, @Param("size") int size);
}
