package com.videostep.locator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.ApiClient;
import com.videostep.locator.service.ApiClientService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 开放平台认证过滤器（方向 C）：对 /api/open/** 校验 HMAC 签名。
 * 校验通过后注入 ROLE_API_CLIENT + request attribute apiClientId，并记录调用计量。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiClientService apiClientService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/open/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);
        String appKey = wrapped.getHeader("X-App-Key");
        String timestamp = wrapped.getHeader("X-Timestamp");
        String nonce = wrapped.getHeader("X-Nonce");
        String signature = wrapped.getHeader("X-Signature");
        String body = wrapped.bodyString();
        try {
            ApiClient client = apiClientService.verify(appKey, timestamp, nonce, signature, body);
            request.setAttribute("apiClientId", client.getId());
            request.setAttribute("apiClientName", client.getName());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "api:" + client.getAppKey(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))));
            chain.doFilter(wrapped, response);
            apiClientService.recordUsage(client.getId(), wrapped.getRequestURI(), body.getBytes().length, 200);
        } catch (ApiClientService.RateLimitedException e) {
            writeError(response, 429, e.getMessage());
        } catch (Exception e) {
            log.warn("[开放平台] 鉴权失败 {}: {}", wrapped.getRequestURI(), e.getMessage());
            writeError(response, 401, "鉴权失败：" + e.getMessage());
        }
    }

    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("data", null);
        response.getWriter().write(objectMapper.writeValueAsString(m));
    }
}
