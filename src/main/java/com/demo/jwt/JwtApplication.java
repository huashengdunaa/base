package com.demo.jwt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;

/**
 * JWT 登录认证 Demo 启动类。
 * 包路径限定在 com.demo.jwt，仅扫描本模块，避免影响 bcrypt / threadpool 模块。
 *
 * 显式排除 RabbitAutoConfiguration：本工程引入了 spring-boot-starter-amqp 用于
 * rabbitmq 异步解耦 Demo，但若在未启动 RabbitMQ 的情况下运行 jwt Demo，自动装配
 * 会尝试连接 RabbitMQ 导致启动失败，因此排除掉。
 */
@SpringBootApplication(exclude = {RabbitAutoConfiguration.class})
public class JwtApplication {

    public static void main(String[] args) {
        SpringApplication.run(JwtApplication.class, args);
    }
}
