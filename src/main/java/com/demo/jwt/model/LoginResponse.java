package com.demo.jwt.model;

/**
 * 登录成功响应体：返回 JWT Token。
 */
public class LoginResponse {

    private String token;
    private String tokenType;   // 固定 "Bearer"
    private long expiresIn;     // 过期秒数

    public LoginResponse(String token, String tokenType, long expiresIn) {
        this.token = token;
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
    }

    public String getToken() { return token; }
    public String getTokenType() { return tokenType; }
    public long getExpiresIn() { return expiresIn; }
}
