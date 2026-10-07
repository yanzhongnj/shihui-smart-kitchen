package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.AdminAuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AdminAuditLogRepository extends BaseMapper<AdminAuditLog> {

    @Insert("INSERT INTO admin_audit_log (operator_id, action, target_type, target_id, detail) " +
            "VALUES (#{operatorId}, #{action}, #{type}, #{targetId}, #{detail})")
    int insertLog(@Param("operatorId") Long operatorId, @Param("action") String action,
                  @Param("type") String type, @Param("targetId") Long targetId,
                  @Param("detail") String detail);

    /** 审计日志分页（含操作人用户名） */
    @Select("<script>SELECT a.id, a.operator_id, a.action, a.target_type, a.target_id, a.detail, a.created_at, u.username "
            + "FROM admin_audit_log a LEFT JOIN users u ON u.id = a.operator_id WHERE 1=1 "
            + "<if test='type != null and type != \"\"'>AND a.target_type = #{type} </if>"
            + "<if test='action != null and action != \"\"'>AND a.action = #{action} </if>"
            + "ORDER BY a.id DESC LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> findPage(@Param("type") String type, @Param("action") String action,
                                       @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM admin_audit_log a WHERE 1=1 "
            + "<if test='type != null and type != \"\"'>AND a.target_type = #{type} </if>"
            + "<if test='action != null and action != \"\"'>AND a.action = #{action} </if></script>")
    long count(@Param("type") String type, @Param("action") String action);
}
