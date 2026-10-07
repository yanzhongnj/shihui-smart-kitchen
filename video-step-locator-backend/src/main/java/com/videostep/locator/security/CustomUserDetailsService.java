package com.videostep.locator.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.videostep.locator.entity.User;
import com.videostep.locator.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("\u7528\u6237\u4e0d\u5b58\u5728: " + username));
        return buildUserDetails(user);
    }

    public UserDetails loadUserById(Long id) {
        User user = userRepository.selectById(id);
        if (user == null) throw new UsernameNotFoundException("\u7528\u6237\u4e0d\u5b58\u5728: " + id);
        return buildUserDetails(user);
    }

    private UserDetails buildUserDetails(User user) {
        // 按用户角色分配权限：ADMIN → ROLE_ADMIN，其余默认 ROLE_USER（供 @PreAuthorize("hasRole('ADMIN')") 使用）
        String role = (user.getRole() == null || user.getRole().isBlank()
                || "USER".equalsIgnoreCase(user.getRole()))
                ? "ROLE_USER" : "ROLE_" + user.getRole().trim().toUpperCase();
        return new org.springframework.security.core.userdetails.User(
                user.getId().toString(),
                user.getPasswordHash(),
                user.getStatus() == 1,
                true, true, true,
                Collections.singletonList(new SimpleGrantedAuthority(role))
        );
    }
}
