package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.request.RegisterRequest;
import com.videostep.locator.entity.User;
import com.videostep.locator.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public User register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        String email = req.getEmail() == null ? "" : req.getEmail().trim();
        if (email.isBlank()) {
            // 游客未填邮箱：自动生成默认邮箱，避免空邮箱撞库
            email = req.getUsername() + "@user.local";
        } else if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("邮箱已被注册");
        }

        User user = User.builder()
                .username(req.getUsername())
                .email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .nickname(req.getNickname() != null ? req.getNickname() : req.getUsername())
                .status(1)
                .build();

        userRepository.insert(user);
        return user;
    }

    public User getById(Long id) {
        User user = userRepository.selectById(id);
        if (user == null) throw new RuntimeException("用户不存在");
        return user;
    }

    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }

    public User updateProfile(Long userId, String nickname, String avatarUrl) {
        User user = getById(userId);
        if (nickname != null) user.setNickname(nickname);
        if (avatarUrl != null) user.setAvatarUrl(avatarUrl);
        userRepository.updateById(user);
        return user;
    }

    /** 平台管理：用户列表（分页 + 关键字 + 状态/角色筛选 + 排序），ADMIN 专用 */
    public Map<String, Object> adminListUsers(int page, int size, String keyword,
                                              String status, String role,
                                              String sortBy, String order) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        boolean asc = "asc".equalsIgnoreCase(order);
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<User>()
                .eq(status != null && !status.isBlank(), User::getStatus, status)
                .eq(role != null && !role.isBlank(), User::getRole, role);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getNickname, keyword)
                    .or().like(User::getEmail, keyword));
        }
        if (sortBy != null && !sortBy.isBlank()) {
            switch (sortBy) {
                case "username" -> qw.orderBy(true, asc, User::getUsername);
                case "role" -> qw.orderBy(true, asc, User::getRole);
                case "status" -> qw.orderBy(true, asc, User::getStatus);
                default -> qw.orderBy(true, asc, User::getCreatedAt);
            }
        } else {
            qw.orderByDesc(User::getCreatedAt);
        }
        long total = userRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = userRepository.selectList(qw).stream()
                .map(u -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", u.getId());
                    m.put("username", u.getUsername());
                    m.put("email", u.getEmail());
                    m.put("nickname", u.getNickname());
                    m.put("avatarUrl", u.getAvatarUrl());
                    m.put("role", u.getRole() != null ? u.getRole() : "USER");
                    m.put("status", u.getStatus());
                    m.put("createdAt", u.getCreatedAt());
                    return m;
                })
                .collect(Collectors.toList());
        return Map.of("items", items, "total", total);
    }

    /** 平台管理：启用/禁用用户（1=启用 0=禁用） */
    public User adminSetStatus(Long id, Integer status) {
        User user = getById(id);
        user.setStatus(status != null && status == 1 ? 1 : 0);
        userRepository.updateById(user);
        return user;
    }

    /** 平台管理：设置角色（ADMIN/USER） */
    public User adminSetRole(Long id, String role) {
        User user = getById(id);
        String r = role != null ? role.trim().toUpperCase() : "USER";
        if (!"ADMIN".equals(r) && !"USER".equals(r)) {
            throw new IllegalArgumentException("非法角色，仅支持 ADMIN / USER");
        }
        user.setRole(r);
        userRepository.updateById(user);
        return user;
    }

    /** 平台管理：新增用户（ADMIN 专用，支持指定角色/状态） */
    public User adminCreateUser(String username, String email, String password,
                                String nickname, String role, Integer status) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("用户名不能为空");
        username = username.trim();
        if (userRepository.existsByUsername(username)) throw new IllegalArgumentException("用户名已存在");
        String mail = (email == null ? "" : email.trim());
        if (mail.isBlank()) {
            mail = username + "@user.local";
        } else if (userRepository.existsByEmail(mail)) {
            throw new IllegalArgumentException("邮箱已被注册");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        User user = User.builder()
                .username(username)
                .email(mail)
                .passwordHash(passwordEncoder.encode(password))
                .nickname(nickname != null && !nickname.isBlank() ? nickname.trim() : username)
                .role(normalizeRole(role))
                .status(status != null && status == 1 ? 1 : 0)
                .build();
        userRepository.insert(user);
        return user;
    }

    /** 平台管理：修改用户（昵称/邮箱/密码/角色/状态，密码留空则不修改） */
    public User adminUpdateUser(Long id, String email, String password,
                                String nickname, String role, Integer status) {
        User user = getById(id);
        if (email != null) {
            String mail = email.trim();
            if (mail.isBlank()) {
                mail = user.getUsername() + "@user.local";
            }
            if (!mail.equals(user.getEmail()) && userRepository.existsByEmail(mail)) {
                throw new IllegalArgumentException("邮箱已被注册");
            }
            user.setEmail(mail);
        }
        if (nickname != null && !nickname.isBlank()) user.setNickname(nickname.trim());
        if (password != null && !password.isBlank()) {
            if (password.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        if (role != null && !role.isBlank()) user.setRole(normalizeRole(role));
        if (status != null) user.setStatus(status == 1 ? 1 : 0);
        userRepository.updateById(user);
        return user;
    }

    private String normalizeRole(String role) {
        String r = role != null ? role.trim().toUpperCase() : "USER";
        if (!"ADMIN".equals(r) && !"USER".equals(r)) {
            throw new IllegalArgumentException("非法角色，仅支持 ADMIN / USER");
        }
        return r;
    }
}
