package com.demo.jwt.model;

/**
 * 用户实体（密码字段已用 BCrypt 哈希，明文不落库）。
 */
public class User {

    private Long id;
    private String username;
    private String passwordHash;

    public User() {}

    public User(Long id, String username, String passwordHash) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }

    public void setId(Long id) { this.id = id; }
    public void setUsername(String username) { this.username = username; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
