package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("org_members")
public class OrgMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long userId;
    /** owner | admin | operator | member */
    private String role;
    /** 0=已移除 1=在职 */
    private Integer status;
    private LocalDateTime joinedAt;

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_OPERATOR = "operator";
    public static final String ROLE_MEMBER = "member";
}
