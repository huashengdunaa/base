package com.demo.rabbitmq.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * RabbitMQ Demo 模块的安全配置。
 *
 * 工程引入了 spring-boot-starter-security，Spring Boot 会自动开启默认安全过滤链，
 * 导致 RabbitMqApplication 下的接口与 Swagger UI 全部返回 401。
 * 本模块仅用于学习 MQ，因此放行所有请求，便于访问注册接口与 Swagger 文档。
 *
 * 访问入口：
 *   Swagger UI:  http://localhost:8080/swagger-ui.html
 *   OpenAPI JSON: http://localhost:8080/v3/api-docs
 *
 * 注意：该配置只在 com.demo.rabbitmq 包内被扫描，不影响 jwt 模块的鉴权逻辑。
 */
@Configuration
public class RabbitMqSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .authorizeRequests()
                .anyRequest().permitAll();
        return http.build();
    }
}
