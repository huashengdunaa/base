package com.demo.rabbitmq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RabbitMQ 异步解耦 Demo 启动类。
 *
 * 场景：用户注册后，通过 RabbitMQ（Topic 交换机）异步发送欢迎邮件 + 初始化用户积分。
 *
 * 包路径限定在 com.demo.rabbitmq，仅扫描本模块，避免与 jwt / inventory 模块互相干扰。
 *
 * 运行前提：本地需启动 RabbitMQ（默认 localhost:5672，guest/guest），
 * 可使用 Docker：
 *   docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3.12-management
 *
 * 运行方式（指定主类，避免与 pom 中默认的 jwt 主类冲突）：
 *   mvn spring-boot:run -Dspring-boot.run.mainClass=com.demo.rabbitmq.RabbitMqApplication
 * 或在 IDE 中直接运行本类的 main 方法。
 */
@SpringBootApplication
public class RabbitMqApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitMqApplication.class, args);
    }
}
