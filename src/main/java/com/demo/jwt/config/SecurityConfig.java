package com.demo.jwt.config;

import com.demo.jwt.filter.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（Spring Boot 2.7 / Security 5.7 风格）。
 *
 * 关键点：
 *   1. 5.7+ 使用 SecurityFilterChain Bean，不再继承已废弃的 WebSecurityConfigurerAdapter
 *   2. JWT 是无状态方案：关闭 CSRF、Session 策略设为 STATELESS
 *   3. /auth/login 放行，其余接口必须认证
 *   4. JwtAuthFilter 用 new 创建并挂到安全过滤链（不声明 @Bean/@Component），
 *      避免它被 Spring Boot 再自动注册到 Servlet 原生过滤链导致重复执行
 *   5. 未认证（缺 Token）统一由 AuthenticationEntryPoint 返回 401 JSON
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // JWT 不依赖 Cookie 会话，关闭 CSRF（跨站请求伪造防护基于会话 Cookie）
                .csrf().disable()
                // 无状态：服务端不创建/不使用 HttpSession
                .sessionManagement()
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                    .and()
                .authorizeRequests()
                    .antMatchers("/auth/login").permitAll()         // 登录接口放行
                    .antMatchers("/inventory/**", "/h2-console/**").permitAll()  // 库存并发 Demo & H2 控制台放行
                    .anyRequest().authenticated()                   // 其余接口都要认证
                    .and()
                // 未认证访问受保护资源时的入口点（缺少/无效身份 → 401 JSON）
                .exceptionHandling()
                    .authenticationEntryPoint(restAuthenticationEntryPoint())
                    .and()
                // JWT 过滤器放在表单登录过滤器之前
                .addFilterBefore(new JwtAuthFilter(),
                        UsernamePasswordAuthenticationFilter.class)
                // 前后端分离不需要默认登录页和 HTTP Basic 弹窗
                .formLogin().disable()
                .httpBasic().disable();
        return http.build();
    }

    /** Spring Security 提供的 BCrypt 密码编码器（与 jBCrypt 哈希格式兼容） */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 未认证入口点：返回 401 + JSON，而不是默认的登录页重定向/HTML */
    private AuthenticationEntryPoint restAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未认证，请先登录\"}");
        };
    }
}
