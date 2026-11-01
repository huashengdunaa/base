package com.demo.rabbitmq.controller;

import com.demo.rabbitmq.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户注册接口。
 *
 * POST /users/register?username=alice&email=alice@demo.com
 *
 * 注册成功后返回生成的 msgId，可到日志中观察该消息被邮件/积分消费者消费的情况，
 * 以及 PointsConsumer 的重试过程。
 */
@Tag(name = "用户注册（RabbitMQ Demo）",
        description = "注册用户后通过 Topic 交换机 user.exchange 异步触发欢迎邮件与积分初始化")
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(
            summary = "用户注册",
            description = "写库后向 user.exchange 发送 routingKey=user.registered 的事件，"
                    + "由 email.queue 与 points.queue 各自异步消费；"
                    + "返回的 msgId 可在日志中追踪该消息的消费与重试过程。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "注册成功，邮件与积分将异步处理"),
            @ApiResponse(responseCode = "400", description = "缺少 username 或 email 参数")
    })
    @PostMapping("/register")
    public Map<String, Object> register(
            @Parameter(description = "用户名", required = true, example = "alice")
            @RequestParam String username,
            @Parameter(description = "接收欢迎邮件的邮箱", required = true, example = "alice@demo.com")
            @RequestParam String email) {
        UserService.RegisterResult result = userService.register(username, email);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", "注册成功，欢迎邮件与积分初始化将异步处理");
        body.put("userId", result.getUserId());
        body.put("username", result.getUsername());
        body.put("email", result.getEmail());
        body.put("msgId", result.getMsgId());
        return body;
    }
}
