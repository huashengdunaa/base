package com.demo.jwt.controller;

import com.demo.jwt.model.User;
import com.demo.jwt.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 受保护接口：GET /users/me
 * 必须携带有效 Token；JwtAuthFilter 校验通过后把 userId 放入
 * SecurityContext，这里用 @AuthenticationPrincipal 直接注入（principal 即 userId）。
 */
@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Long userId) {
        User user = userService.findById(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("message", "Token 校验通过，已获取当前用户信息");
        return result;
    }
}
