package com.videostep.locator.controller;

import com.videostep.locator.dto.request.LoginRequest;
import com.videostep.locator.dto.request.RegisterRequest;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.User;
import com.videostep.locator.security.JwtTokenProvider;
import com.videostep.locator.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        User user = userService.register(req);
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());

        Map<String, Object> data = Map.of(
                "token", token,
                "expiresIn", 86400000,
                "user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "nickname", user.getNickname(),
                        "role", user.getRole() != null ? user.getRole() : "USER"
                )
        );
        return ApiResponse.success("注册成功", data);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        log.info("auth login attempt: username={}", req.getUsername());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
        } catch (Exception e) {
            log.warn("auth login FAILED: username={} reason={}", req.getUsername(), e.getClass().getSimpleName());
            throw e;
        }

        User user = userService.getByUsername(req.getUsername());
        log.info("auth login success: username={} userId={}", req.getUsername(), user.getId());
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());

        Map<String, Object> data = Map.of(
                "token", token,
                "expiresIn", 86400000,
                "user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "nickname", user.getNickname(),
                        "role", user.getRole() != null ? user.getRole() : "USER"
                )
        );
        return ApiResponse.success(data);
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        // 客户端只需重新使用旧 token 访问本接口
        // 实际刷新逻辑由 JWT 过滤器处理
        return ApiResponse.success(Map.of("message", "Token is valid"));
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> getProfile(
            @RequestAttribute(value = "userId", required = false) Long userId) {
        if (userId == null) {
            return ApiResponse.error(401, "未登录或登录已过期，请重新登录");
        }
        User user = userService.getById(userId);
        // 用 HashMap（允许 null 值），避免 Map.of 因 nickname/avatarUrl 为 null 抛 NPE
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("nickname", user.getNickname());
        data.put("avatarUrl", user.getAvatarUrl());
        data.put("role", user.getRole() != null ? user.getRole() : "USER");
        data.put("createdAt", user.getCreatedAt());
        return ApiResponse.success(data);
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, String> body) {
        User user = userService.updateProfile(userId,
                body.get("nickname"), body.get("avatarUrl"));
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("nickname", user.getNickname());
        result.put("avatarUrl", user.getAvatarUrl());
        return ApiResponse.success(result);
    }
}
