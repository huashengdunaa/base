package com.demo.usermanage;

import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;

/**
 * 用户管理系统（RESTful API）启动类。
 *
 * 技术栈：Spring MVC + MyBatis 分层结构，当前用内存伪数据代替 MySQL，
 * 免安装数据库即可直接运行；后续接 MySQL 时：
 *   1. 去掉下方 exclude 中的 DataSource / MyBatis 自动配置排除；
 *   2. 在类上增加 @MapperScan("com.demo.usermanage.mapper")；
 *   3. 删除 InMemoryUserMapper，UserMapper 上的注解 SQL 无需改动。
 *
 * 包路径限定在 com.demo.usermanage，仅扫描本模块，不影响 jwt / rabbitmq / inventory 模块。
 *
 * 排除项说明：
 *   - DataSourceAutoConfiguration / HibernateJpaAutoConfiguration：本 Demo 不连真实数据库
 *   - MybatisAutoConfiguration：不启用 SqlSessionFactory（Mapper 由内存实现类提供 Bean）
 *   - SecurityAutoConfiguration 等：本作业不做登录认证，放行全部接口
 *   - RabbitAutoConfiguration：本机未启动 RabbitMQ 时避免连接失败
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        MybatisAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        RabbitAutoConfiguration.class
})
public class UserManageApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserManageApplication.class, args);
    }
}
