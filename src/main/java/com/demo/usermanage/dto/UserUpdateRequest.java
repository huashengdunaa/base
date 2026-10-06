package com.demo.usermanage.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

/**
 * 更新用户请求体：所有字段可选，仅更新传入的非 null 字段（动态更新）。
 * 注意 JSR-303 对 null 值直接放行：字段不传不校验，传了非法值才报错。
 */
public class UserUpdateRequest {

    /** 用户名：不传则不改；不能更新为空白串 */
    @Size(max = 50, message = "用户名最长 50 个字符")
    private String username;

    /** 密码：不传则不改；传了必须满足长度要求 */
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 位之间")
    private String password;

    /** 邮箱：不传则不改；传了必须符合格式（空串视为非法格式） */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 状态：不传则不改；只允许 0 禁用 / 1 启用（-1 删除走专用 DELETE 接口） */
    @Min(value = 0, message = "状态值非法")
    @Max(value = 1, message = "状态值非法")
    private Integer status;

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
}
