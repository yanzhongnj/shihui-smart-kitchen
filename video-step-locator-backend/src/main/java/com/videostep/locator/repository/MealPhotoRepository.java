package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.MealPhoto;
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

/**
 * 拍照打卡仓储。软删/回收站/Admin 列表均走原生 SQL（MyBatis-Plus 逻辑删除开启时
 * selectById/deleteById 会自动过滤/转软删，列已删行/恢复/物理删须自定义 SQL）。
 */
@Mapper
public interface MealPhotoRepository extends BaseMapper<MealPhoto> {

    /** 按 ID 查未删行（属主校验用） */
    @Select("SELECT * FROM meal_photos WHERE id = #{id} AND deleted = 0")
    MealPhoto findActiveById(@Param("id") Long id);

    // ---------------- 回收站（软删除治理，仅 ADMIN） ----------------

    /** 软删除并记录删除时间 */
    @Update("UPDATE meal_photos SET deleted = 1, deleted_at = NOW() WHERE id = #{id} AND deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /** 恢复 */
    @Update("UPDATE meal_photos SET deleted = 0, deleted_at = NULL WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 按 ID 查已删行 */
    @Select("SELECT * FROM meal_photos WHERE id = #{id} AND deleted = 1")
    MealPhoto findDeletedById(@Param("id") Long id);

    /** 已删记录分页（含用户名） */
    @Select("<script>SELECT m.*, u.username FROM meal_photos m LEFT JOIN users u ON u.id = m.user_id WHERE m.deleted = 1 "
            + "<if test='userId != null'>AND m.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (u.username LIKE CONCAT('%', #{keyword}, '%') OR CAST(m.recognition_json AS CHAR) LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "ORDER BY m.deleted_at DESC, m.id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findDeletedPage(@Param("userId") Long userId, @Param("keyword") String keyword,
                                              @Param("offset") int offset, @Param("size") int size);

    /** 已删记录计数 */
    @Select("<script>SELECT COUNT(*) FROM meal_photos m LEFT JOIN users u ON u.id = m.user_id WHERE m.deleted = 1 "
            + "<if test='userId != null'>AND m.user_id = #{userId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND (u.username LIKE CONCAT('%', #{keyword}, '%') OR CAST(m.recognition_json AS CHAR) LIKE CONCAT('%', #{keyword}, '%')) </if></script>")
    long countDeleted(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 归档（INSERT…SELECT 进归档表，彻底删除前调用） */
    @Insert("INSERT INTO meal_photos_archive (original_id, user_id, photo_path, meal_type, record_date, recognition_json, portion_json, status, diet_record_id, corrected_at, deleted_at, archived_at, archived_by) "
            + "SELECT id, user_id, photo_path, meal_type, record_date, recognition_json, portion_json, status, diet_record_id, corrected_at, deleted_at, NOW(), #{archivedBy} "
            + "FROM meal_photos WHERE id = #{id} AND deleted = 1")
    int archiveRow(@Param("id") Long id, @Param("archivedBy") Long archivedBy);

    /** 彻底删除（物理删，须先归档） */
    @Delete("DELETE FROM meal_photos WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);

    /** 超期软删 ID（自动清理用） */
    @Select("SELECT id FROM meal_photos WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{before} ORDER BY id LIMIT #{offset}, #{size}")
    List<Long> findExpiredIds(@Param("before") LocalDateTime before, @Param("offset") int offset, @Param("size") int size);

    // ---------------- Admin 管理页（非软删记录） ----------------

    /** Admin 分页列表（含用户名；keyword 匹配用户名/识别摘要；日期范围/餐次/状态筛选） */
    @Select("<script>SELECT m.*, u.username FROM meal_photos m LEFT JOIN users u ON u.id = m.user_id WHERE m.deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'>AND (u.username LIKE CONCAT('%', #{keyword}, '%') OR CAST(m.recognition_json AS CHAR) LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "<if test='dateFrom != null'>AND m.record_date &gt;= #{dateFrom} </if>"
            + "<if test='dateTo != null'>AND m.record_date &lt;= #{dateTo} </if>"
            + "<if test='mealType != null and mealType != \"\"'>AND m.meal_type = #{mealType} </if>"
            + "<if test='status != null and status != \"\"'>AND m.status = #{status} </if>"
            + "ORDER BY m.id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findAdminPage(@Param("keyword") String keyword, @Param("dateFrom") LocalDate dateFrom,
                                            @Param("dateTo") LocalDate dateTo, @Param("mealType") String mealType,
                                            @Param("status") String status, @Param("offset") int offset, @Param("size") int size);

    /** Admin 分页计数 */
    @Select("<script>SELECT COUNT(*) FROM meal_photos m LEFT JOIN users u ON u.id = m.user_id WHERE m.deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'>AND (u.username LIKE CONCAT('%', #{keyword}, '%') OR CAST(m.recognition_json AS CHAR) LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "<if test='dateFrom != null'>AND m.record_date &gt;= #{dateFrom} </if>"
            + "<if test='dateTo != null'>AND m.record_date &lt;= #{dateTo} </if>"
            + "<if test='mealType != null and mealType != \"\"'>AND m.meal_type = #{mealType} </if>"
            + "<if test='status != null and status != \"\"'>AND m.status = #{status} </if></script>")
    long countAdmin(@Param("keyword") String keyword, @Param("dateFrom") LocalDate dateFrom,
                    @Param("dateTo") LocalDate dateTo, @Param("mealType") String mealType,
                    @Param("status") String status);

    // ---------------- Admin 统计 ----------------

    @Select("SELECT COUNT(*) FROM meal_photos WHERE deleted = 0")
    long countAll();

    @Select("SELECT COUNT(*) FROM meal_photos WHERE deleted = 0 AND record_date = CURDATE()")
    long countToday();

    @Select("SELECT COUNT(*) FROM meal_photos WHERE deleted = 0 AND status IN ('confirmed','corrected')")
    long countConfirmed();

    @Select("SELECT status, COUNT(*) AS c FROM meal_photos WHERE deleted = 0 GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("SELECT meal_type, COUNT(*) AS c FROM meal_photos WHERE deleted = 0 GROUP BY meal_type")
    List<Map<String, Object>> countByMealType();

    @Select("SELECT m.user_id, u.username, COUNT(*) AS c FROM meal_photos m LEFT JOIN users u ON u.id = m.user_id WHERE m.deleted = 0 GROUP BY m.user_id, u.username ORDER BY c DESC LIMIT #{limit}")
    List<Map<String, Object>> topUsers(@Param("limit") int limit);

    /** 已确认记录关联 diet 的平均热量 */
    @Select("SELECT COALESCE(AVG(d.calories),0) FROM diet_records d JOIN meal_photos m ON m.id = d.photo_id WHERE m.deleted = 0")
    double avgCalories();
}
