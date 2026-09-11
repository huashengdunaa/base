package com.demo.rabbitmq.model;

import java.io.Serializable;

/**
 * 用户注册事件消息体。
 *
 * 幂等性关键：msgId（全局唯一消息 ID）。
 * 生产者发送时生成 msgId 并写入 MessageProperties.messageId；
 * 消费者拿到后用它做去重，避免同一条消息被重复处理（例如重投、网络抖动导致的重复投递）。
 */
public class UserRegisteredEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 邮箱（用于发送欢迎邮件） */
    private String email;

    public UserRegisteredEvent() {
    }

    public UserRegisteredEvent(Long userId, String username, String email) {
        this.userId = userId;
        this.username = username;
        this.email = email;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @Override
    public String toString() {
        return "UserRegisteredEvent{" +
                "userId=" + userId +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                '}';
    }
}
