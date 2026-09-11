package com.demo.rabbitmq.service;

import com.demo.rabbitmq.model.UserRegisteredEvent;
import com.demo.rabbitmq.producer.UserEventProducer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用户服务：模拟注册 + 发送 MQ 事件。
 *
 * 解耦要点：
 *   register() 只做"写库 + 发消息"，不直接调用邮件/积分逻辑。
 *   邮件发送、积分初始化由各自的消费者异步完成，主流程快速返回。
 */
@Service
public class UserService {

    /** 模拟用户表（内存） */
    private final Map<Long, String> users = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    private final UserEventProducer userEventProducer;

    public UserService(UserEventProducer userEventProducer) {
        this.userEventProducer = userEventProducer;
    }

    /**
     * 用户注册。
     *
     * @param username 用户名
     * @param email    邮箱
     * @return 注册结果（含生成的 msgId，便于关联消息追踪）
     */
    public RegisterResult register(String username, String email) {
        // 1. 写库（模拟）
        Long userId = idGenerator.incrementAndGet();
        users.put(userId, username);
        System.out.println("[UserService] 用户注册成功, userId=" + userId + ", username=" + username);

        // 2. 发送"用户已注册"事件到 MQ（异步解耦：邮件、积分由消费者处理）
        UserRegisteredEvent event = new UserRegisteredEvent(userId, username, email);
        String msgId = userEventProducer.sendUserRegistered(event);

        return new RegisterResult(userId, username, email, msgId);
    }

    /** 注册结果 */
    public static class RegisterResult {
        private final Long userId;
        private final String username;
        private final String email;
        private final String msgId;

        public RegisterResult(Long userId, String username, String email, String msgId) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.msgId = msgId;
        }

        public Long getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getEmail() { return email; }
        public String getMsgId() { return msgId; }
    }
}
