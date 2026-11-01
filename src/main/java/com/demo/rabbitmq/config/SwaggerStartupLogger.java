package com.demo.rabbitmq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动完成后打印 Swagger 访问地址，省去每次手动拼端口。
 *
 * 监听 ApplicationReadyEvent，此时 Web 容器已就绪，可从
 * ServletWebServerApplicationContext 取到真实监听端口（含随机端口场景）。
 */
@Component
public class SwaggerStartupLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(SwaggerStartupLogger.class);

    private final ServletWebServerApplicationContext webServerContext;

    public SwaggerStartupLogger(ServletWebServerApplicationContext webServerContext) {
        this.webServerContext = webServerContext;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int port = webServerContext.getWebServer().getPort();
        String baseUrl = "http://localhost:" + port;

        log.info("Swagger UI 访问地址: {}/swagger-ui.html", baseUrl);
        log.info("OpenAPI 文档地址: {}/v3/api-docs", baseUrl);
    }
}
