package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.DishNutrition;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 菜品级营养库仓储。软删/回收站对齐 r114/r144 体系（deleted/deleted_at + dish_nutrition_db_archive 归档）。
 * 注意：MyBatis-Plus 逻辑删除开启后 selectById/selectList 自动过滤 deleted=1、deleteById 自动转软删；
 * 因此自定义 SQL（搜索/Admin/统计/回收站）须显式加 deleted = 0 / 1 条件。
 */
@Mapper
public interface DishNutritionRepository extends BaseMapper<DishNutrition> {

    @Select("SELECT * FROM dish_nutrition_db WHERE dish_name = #{name} AND deleted = 0 LIMIT 1")
    DishNutrition findByDishName(@Param("name") String name);

    /** 按菜名查已软删行（用于重复创建时的友好提示） */
    @Select("SELECT * FROM dish_nutrition_db WHERE dish_name = #{name} AND deleted = 1 LIMIT 1")
    DishNutrition findDeletedByName(@Param("name") String name);

    /** 模糊搜索（用户手动选择 / 语音补充辅助），复核优先 + 命中热度优先 */
    @Select("SELECT * FROM dish_nutrition_db WHERE deleted = 0 AND dish_name LIKE CONCAT('%', #{kw}, '%') "
            + "ORDER BY is_verified DESC, hit_count DESC, id DESC LIMIT #{limit}")
    List<DishNutrition> search(@Param("kw") String kw, @Param("limit") int limit);

    /** 命中次数 +1（用户确认/换选候选回写，驱动扩充） */
    @Update("UPDATE dish_nutrition_db SET hit_count = hit_count + 1 WHERE id = #{id}")
    int incrementHit(@Param("id") Long id);

    // ---------------- 软删 / 回收站（仅 ADMIN） ----------------

    /** 软删除并记录删除时间（回收站入口） */
    @Update("UPDATE dish_nutrition_db SET deleted = 1, deleted_at = NOW() WHERE id = #{id} AND deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /** 恢复 */
    @Update("UPDATE dish_nutrition_db SET deleted = 0, deleted_at = NULL WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 按 ID 查已删行 */
    @Select("SELECT * FROM dish_nutrition_db WHERE id = #{id} AND deleted = 1")
    DishNutrition findDeletedById(@Param("id") Long id);

    /** 已删记录分页（keyword 匹配菜名/来源） */
    @Select("<script>SELECT * FROM dish_nutrition_db WHERE deleted = 1 "
            + "<if test='keyword != null and keyword != \"\"'>AND (dish_name LIKE CONCAT('%', #{keyword}, '%') OR source LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "ORDER BY deleted_at DESC, id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findDeletedPage(@Param("keyword") String keyword,
                                              @Param("offset") int offset, @Param("size") int size);

    /** 已删记录计数 */
    @Select("<script>SELECT COUNT(*) FROM dish_nutrition_db WHERE deleted = 1 "
            + "<if test='keyword != null and keyword != \"\"'>AND (dish_name LIKE CONCAT('%', #{keyword}, '%') OR source LIKE CONCAT('%', #{keyword}, '%')) </if></script>")
    long countDeleted(@Param("keyword") String keyword);

    /** 归档（INSERT…SELECT 进归档表，彻底删除前调用） */
    @Insert("INSERT INTO dish_nutrition_db_archive (original_id, dish_name, aliases, cuisine, main_ingredients, nutrition_per_serving, serving_grams, is_verified, source, hit_count, deleted_at, archived_at, archived_by) "
            + "SELECT id, dish_name, aliases, cuisine, main_ingredients, nutrition_per_serving, serving_grams, is_verified, source, hit_count, deleted_at, NOW(), #{archivedBy} "
            + "FROM dish_nutrition_db WHERE id = #{id} AND deleted = 1")
    int archiveRow(@Param("id") Long id, @Param("archivedBy") Long archivedBy);

    /** 彻底删除（物理删，须先归档） */
    @Delete("DELETE FROM dish_nutrition_db WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);

    /** 超期软删 ID（自动清理用） */
    @Select("SELECT id FROM dish_nutrition_db WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{before} ORDER BY id LIMIT #{offset}, #{size}")
    List<Long> findExpiredIds(@Param("before") LocalDateTime before, @Param("offset") int offset, @Param("size") int size);

    // ---------------- Admin 管理（未删记录） ----------------

    @Select("<script>SELECT * FROM dish_nutrition_db WHERE deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'>AND dish_name LIKE CONCAT('%', #{keyword}, '%') </if>"
            + "<if test='verified != null'>AND is_verified = #{verified} </if>"
            + "<if test='source != null and source != \"\"'>AND source = #{source} </if>"
            + "ORDER BY <choose><when test='sortHit'>hit_count DESC</when><otherwise>id DESC</otherwise></choose> "
            + "LIMIT #{offset}, #{size}</script>")
    List<DishNutrition> findAdminPage(@Param("keyword") String keyword, @Param("verified") Integer verified,
                                      @Param("source") String source, @Param("sortHit") boolean sortHit,
                                      @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM dish_nutrition_db WHERE deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'>AND dish_name LIKE CONCAT('%', #{keyword}, '%') </if>"
            + "<if test='verified != null'>AND is_verified = #{verified} </if>"
            + "<if test='source != null and source != \"\"'>AND source = #{source} </if></script>")
    long countAdmin(@Param("keyword") String keyword, @Param("verified") Integer verified,
                    @Param("source") String source);

    @Select("SELECT COUNT(*) FROM dish_nutrition_db WHERE deleted = 0")
    long countAll();

    @Select("SELECT COUNT(*) FROM dish_nutrition_db WHERE deleted = 0 AND is_verified = 1")
    long countVerified();

    @Select("SELECT COUNT(*) FROM dish_nutrition_db WHERE deleted = 0 AND source = 'ai' AND is_verified = 0")
    long countAiCandidates();

    @Select("SELECT dish_name, hit_count FROM dish_nutrition_db WHERE deleted = 0 ORDER BY hit_count DESC, id DESC LIMIT #{limit}")
    List<Map<String, Object>> topByHit(@Param("limit") int limit);
}
