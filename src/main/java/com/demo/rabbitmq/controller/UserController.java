package com.demo.rabbitmq.controller;

import com.demo.rabbitmq.service.UserService;
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
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestParam String username,
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
