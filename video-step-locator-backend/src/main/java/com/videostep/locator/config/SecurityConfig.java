package com.videostep.locator.config;

import com.videostep.locator.security.ApiKeyAuthFilter;
import com.videostep.locator.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          ApiKeyAuthFilter apiKeyAuthFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/ws/**").permitAll()
                // 我的视频列表：游客按 deviceId 看本设备下载/上传的视频；登录用户带 token 时仍会设置 userId
                .requestMatchers(HttpMethod.GET, "/api/videos").permitAll()
                // 游客可访问的公开只读/体验接口（解析/预览/下载受配额，服务层校验可见性）
                .requestMatchers(HttpMethod.GET, "/api/recipes/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/videos/*/steps").permitAll()
                // 步骤循环为内存态、无用户归属；游客（体验视频）同样可用循环播放
                .requestMatchers(HttpMethod.POST, "/api/steps/*/loop").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/steps/*/loop").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/videos/*/reviews").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/videos/*/file").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/download/preview").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/download/parse").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/download/resolve").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/download/start").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/download/tasks/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/download/tasks/*/retry", "/api/download/tasks/*/cancel").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/app/version").permitAll()
                // APP 扫码下载：下载 APK + 下载信息（公开，无需登录）
                .requestMatchers(HttpMethod.GET, "/api/app/download", "/api/app/download/info").permitAll()
                // A1 白标：公开品牌配置读取（预装/设备激活页/未登录场景）
                .requestMatchers(HttpMethod.GET, "/api/brand/**").permitAll()
                // 公开视频流（体验视频，游客可播放，支持 Range）
                .requestMatchers(HttpMethod.GET, "/api/videos/*/stream").permitAll()
                // 游客体验视频列表/详情
                .requestMatchers("/api/experience/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/share/*").permitAll()
                .requestMatchers("/s/**").permitAll()
                // 酿味创作站外分享：匿名只读解析
                .requestMatchers(HttpMethod.GET, "/api/creation/share/**").permitAll()
                // 文本语音命令：仅做本地正则匹配，无需登录（游客 / 过期 token 也可用）
                .requestMatchers(HttpMethod.POST, "/api/voice/command").permitAll()
                // 方向 C：开放平台端点（自带 HMAC 鉴权，由 ApiKeyAuthFilter 处理）
                .requestMatchers("/api/open/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
