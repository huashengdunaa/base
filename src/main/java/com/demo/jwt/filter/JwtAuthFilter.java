package com.demo.jwt.filter;

import com.demo.jwt.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器（Spring Security 版，基于 OncePerRequestFilter）。
 *
 * 注意：这里不再使用 @Component。过滤器由 SecurityConfig 中 new 出来并通过
 * addFilterBefore 挂入 Spring Security 过滤链；若同时声明为 Bean，会被
 * Spring Boot 再注册到 Servlet 原生过滤链，导致同一请求执行两次。
 *
 * 职责：
 *   1. 从 Authorization: Bearer <token> 取 Token（没有则放行，
 *      受保护资源由 SecurityConfig 的 AuthenticationEntryPoint 返回 401）
 *   2. 校验签名 + 过期时间；通过后把身份写入 SecurityContext
 *   3. Token 过期/非法 → 直接返回 401
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        // 未携带 Token：匿名身份继续走过滤链，/auth/login 在配置中 permitAll，
        // 其他受保护接口会被授权过滤器拦下并触发 AuthenticationEntryPoint(401)
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length());
        try {
            Claims claims = JwtUtil.parse(token);
            Long userId = Long.valueOf(claims.getSubject());

            // 写入 Spring Security 上下文：principal = userId，凭证为 null，暂无权限
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (ExpiredJwtException e) {
            // exp 已过期
            writeUnauthorized(response, "Token 已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            // 签名错误/格式非法
            writeUnauthorized(response, "Token 无效");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
    }
}
