package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.Org;
import com.videostep.locator.entity.OrgMember;
import com.videostep.locator.service.OrgService;
import com.videostep.locator.service.RecipeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * B 端企业接口（方向 B1：企业账号 / 成员协作；B2：内容工作台 / 看板 / 月报）。
 * 鉴权：/api/** 默认 authenticated；企业内操作由 OrgAccessService 校验成员/角色。
 */
@RestController
@RequestMapping("/api/orgs")
@RequiredArgsConstructor
@Slf4j
public class OrgController {

    private final OrgService orgService;
    private final RecipeService recipeService;

    /** 创建企业（首个用户自动 owner） */
    @PostMapping
    public ApiResponse<Org> create(@RequestAttribute("userId") Long userId,
                                   @RequestBody Map<String, String> body) {
        return ApiResponse.success(orgService.createOrg(userId, body.get("name")));
    }

    /** 我的企业（无企业返回 data=null） */
    @GetMapping("/mine")
    public ApiResponse<Map<String, Object>> mine(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(orgService.getMyOrg(userId));
    }

    /** 更新企业信息（owner/admin） */
    @PutMapping("/{orgId}")
    public ApiResponse<Org> update(@RequestAttribute("userId") Long userId,
                                   @PathVariable Long orgId,
                                   @RequestBody Map<String, String> body) {
        return ApiResponse.success(orgService.updateOrg(userId, orgId, body.get("name"), body.get("logo")));
    }

    // ---------------- 成员 ----------------

    @GetMapping("/{orgId}/members")
    public ApiResponse<Map<String, Object>> members(@RequestAttribute("userId") Long userId,
                                                    @PathVariable Long orgId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(orgService.listMembers(userId, orgId, page, size));
    }

    @PostMapping("/{orgId}/members")
    public ApiResponse<Map<String, Object>> addMember(@RequestAttribute("userId") Long userId,
                                                      @PathVariable Long orgId,
                                                      @RequestBody Map<String, String> body) {
        return ApiResponse.success(orgService.addMember(userId, orgId, body.get("account"), body.get("role")));
    }

    @PutMapping("/members/{memberId}/role")
    public ApiResponse<OrgMember> updateRole(@RequestAttribute("userId") Long userId,
                                             @PathVariable Long memberId,
                                             @RequestBody Map<String, String> body) {
        return ApiResponse.success(orgService.updateMemberRoleByMemberId(userId, memberId, body.get("role")));
    }

    @DeleteMapping("/members/{memberId}")
    public ApiResponse<Map<String, Object>> removeMember(@RequestAttribute("userId") Long userId,
                                                         @PathVariable Long memberId) {
        orgService.removeMemberByMemberId(userId, memberId);
        return ApiResponse.success(Map.of("message", "已移除成员"));
    }

    // ---------------- 统计 / 计费 / 订阅 ----------------

    @GetMapping("/{orgId}/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestAttribute("userId") Long userId,
                                                  @PathVariable Long orgId) {
        return ApiResponse.success(orgService.orgStats(userId, orgId));
    }

    // ---------------- B2：企业内容工作台 ----------------

    /** 企业菜谱库（按 org_id 隔离） */
    @GetMapping("/{orgId}/recipes")
    public ApiResponse<List<Map<String, Object>>> orgRecipes(@RequestAttribute("userId") Long userId,
                                                             @PathVariable Long orgId,
                                                             @RequestParam(required = false) String cuisine,
                                                             @RequestParam(required = false) String taste) {
        orgService.requireMemberAccess(userId, orgId);
        return ApiResponse.success(recipeService.listForOrg(orgId, cuisine, taste));
    }

    /** 企业任务看板：状态分布 / 成功率 / 平均耗时 / 今日处理量 */
    @GetMapping("/{orgId}/tasks")
    public ApiResponse<Map<String, Object>> orgTasks(@RequestAttribute("userId") Long userId,
                                                     @PathVariable Long orgId) {
        orgService.requireMemberAccess(userId, orgId);
        return ApiResponse.success(orgService.orgTaskStats(orgId));
    }

    /** 企业月报 */
    @GetMapping("/{orgId}/reports/monthly")
    public ApiResponse<Map<String, Object>> orgReport(@RequestAttribute("userId") Long userId,
                                                      @PathVariable Long orgId,
                                                      @RequestParam(required = false) String month) {
        orgService.requireMemberAccess(userId, orgId);
        return ApiResponse.success(orgService.orgReport(orgId, month));
    }

    @GetMapping("/{orgId}/billing")
    public ApiResponse<Map<String, Object>> billing(@RequestAttribute("userId") Long userId,
                                                    @PathVariable Long orgId) {
        orgService.requireMemberAccess(userId, orgId);
        return ApiResponse.success(orgService.billingSummary(orgId));
    }

    @GetMapping("/{orgId}/invoices")
    public ApiResponse<Map<String, Object>> invoices(@RequestAttribute("userId") Long userId,
                                                     @PathVariable Long orgId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(orgService.listInvoices(userId, orgId, page, size));
    }

    @PostMapping("/{orgId}/subscribe")
    public ApiResponse<Map<String, Object>> subscribe(@RequestAttribute("userId") Long userId,
                                                      @PathVariable Long orgId,
                                                      @RequestBody Map<String, String> body) {
        return ApiResponse.success(orgService.subscribe(userId, orgId, body.get("planCode")));
    }

    /** 可用套餐列表（登录用户即可查看） */
    @GetMapping("/plans")
    public ApiResponse<List<Map<String, Object>>> plans() {
        return ApiResponse.success(orgService.listPlans());
    }
}
