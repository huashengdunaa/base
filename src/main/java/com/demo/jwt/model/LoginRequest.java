package com.demo.jwt.model;

/**
 * 登录请求体：POST /auth/login
 */
public class LoginRequest {

    private String username;
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
