package com.demo.jwt.controller;

import com.demo.jwt.model.LoginRequest;
import com.demo.jwt.model.LoginResponse;
import com.demo.jwt.model.User;
import com.demo.jwt.service.UserService;
import com.demo.jwt.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 认证接口：POST /auth/login
 * 校验用户名密码，成功返回 JWT Token（2h 有效）。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        User user = userService.findByUsername(req.getUsername());
        if (user == null || !userService.checkPassword(user, req.getPassword())) {
            // 用户不存在或密码错误统一返回 401，避免暴露用户是否存在
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Collections.singletonMap("message", "用户名或密码错误"));
        }
        String token = JwtUtil.generateToken(user.getId(), user.getUsername());
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", JwtUtil.getExpirationSeconds()));
    }
}
