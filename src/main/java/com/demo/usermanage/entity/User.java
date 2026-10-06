package com.demo.usermanage.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 t_user：
 * id, username, password, email, status, created_at
 *
 * status 约定（表中无独立 deleted 字段，用 status 表达逻辑删除）：
 *   1  = 启用
 *   0  = 禁用
 *  -1  = 已逻辑删除（查询/更新均过滤此状态）
 */
public class User {

    /** 主键 */
    private Long id;

    /** 用户名（唯一，未逻辑删除范围内） */
    private String username;

    /** 密码（存 BCrypt 哈希值；@JsonIgnore 保证任何接口都不返回该字段） */
    @JsonIgnore
    private String password;

    /** 邮箱 */
    private String email;

    /** 状态：1 启用 / 0 禁用 / -1 已删除 */
    private Integer status;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
