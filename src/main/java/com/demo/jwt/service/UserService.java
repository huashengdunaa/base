package com.demo.jwt.service;

import com.demo.jwt.model.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户服务：模拟数据库 + BCrypt 密码校验。
 * 真实项目应替换为 MyBatis/JPA 访问真实数据库。
 *
 * 密码编解码统一走 Spring Security 的 PasswordEncoder（SecurityConfig 中
 * 配置的 BCryptPasswordEncoder），其哈希格式与 jBCrypt 完全兼容。
 */
@Service
public class UserService {

    private final Map<Long, User> userById = new HashMap<>();
    private final Map<String, User> userByName = new HashMap<>();

    private final PasswordEncoder passwordEncoder;

    public UserService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void init() {
        // 预置两个用户（密码已用 BCrypt 哈希，明文不落库）
        save(1L, "alice", passwordEncoder.encode("AlicePass2026!"));
        save(2L, "bob",   passwordEncoder.encode("Bob@Secure#789"));
    }

    private void save(Long id, String username, String passwordHash) {
        User u = new User(id, username, passwordHash);
        userById.put(id, u);
        userByName.put(username, u);
    }

    public User findByUsername(String username) {
        return userByName.get(username);
    }

    public User findById(Long id) {
        return userById.get(id);
    }

    /** 校验明文密码与哈希是否匹配 */
    public boolean checkPassword(User user, String plainPassword) {
        if (user == null || plainPassword == null) {
            return false;
        }
        return passwordEncoder.matches(plainPassword, user.getPasswordHash());
    }
}
