package com.demo.bcrypt;

import org.mindrot.jbcrypt.BCrypt;

import java.util.HashMap;
import java.util.Map;

/**
 * 03 实战封装：密码服务工具类 + 模拟用户注册/登录
 *
 * 演示场景：
 *   1. 用户注册：明文密码 → BCrypt 哈希后存储（DB 中不存明文）
 *   2. 用户登录：取出哈希 → BCrypt.checkpw 验证
 *   3. 密码升级：cost=10 的旧哈希迁移到 cost=12（下次登录时静默升级）
 */
public class Demo03_PasswordService {

    /** 生产环境建议 cost=12，安全性与体验的平衡点 */
    private static final int DEFAULT_COST = 12;

    // ====== 密码服务工具方法 ======

    /** 加密密码（注册/修改密码时调用） */
    public static String hashPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(DEFAULT_COST));
    }

    /** 验证密码（登录时调用） */
    public static boolean verifyPassword(String plainPassword, String hashedPassword) {
        if (plainPassword == null || hashedPassword == null || hashedPassword.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, hashedPassword);
        } catch (IllegalArgumentException e) {
            // 哈希格式不合法（被篡改或非 BCrypt 格式）
            return false;
        }
    }

    /** 检查是否需要升级 cost（旧哈希 cost 低于目标值时返回 true） */
    public static boolean needsUpgrade(String hashedPassword, int targetCost) {
        if (hashedPassword == null || hashedPassword.length() < 7) {
            return true;
        }
        try {
            int currentCost = Integer.parseInt(hashedPassword.substring(4, 6));
            return currentCost < targetCost;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    // ====== 模拟用户注册与登录 ======

    /** 模拟数据库用户表: username → hashedPassword */
    private static final Map<String, String> userDB = new HashMap<>();

    public static void main(String[] args) {
        // ---- 场景1: 用户注册 ----
        System.out.println("========== 用户注册 ==========");
        register("alice", "AlicePass2026!");
        register("bob", "Bob@Secure#789");
        register("alice", "AlicePass2026!"); // 重复注册（会报错）

        // ---- 场景2: 用户登录 ----
        System.out.println("\n========== 用户登录 ==========");
        login("alice", "AlicePass2026!");   // 正确密码
        login("alice", "WrongPassword");    // 错误密码
        login("charlie", "anyPassword");   // 用户不存在

        // ---- 场景3: 密码强度升级 ----
        System.out.println("\n========== 密码升级 ==========");
        // 模拟旧用户哈希（cost=10），下次登录时静默升级到 cost=12
        String oldHash = BCrypt.hashpw("OldPass123", BCrypt.gensalt(10));
        userDB.put("legacy_user", oldHash);
        System.out.println("旧哈希 cost=10: " + oldHash.substring(0, 7) + "...");

        // legacy_user 登录，验证成功后自动升级
        loginWithUpgrade("legacy_user", "OldPass123");

        System.out.println("\n升级后哈希: " + userDB.get("legacy_user").substring(0, 7) + "...");
        System.out.println("已升级: " + needsUpgrade(userDB.get("legacy_user"), DEFAULT_COST));
    }

    /** 注册：密码哈希后存入"数据库" */
    private static void register(String username, String plainPassword) {
        if (userDB.containsKey(username)) {
            System.out.println("注册失败: 用户 " + username + " 已存在");
            return;
        }
        String hashed = hashPassword(plainPassword);
        userDB.put(username, hashed);
        System.out.println("注册成功: " + username + " → 哈希已存储: " + hashed.substring(0, 20) + "...");
    }

    /** 登录：从"数据库"取出哈希进行验证 */
    private static void login(String username, String plainPassword) {
        String storedHash = userDB.get(username);
        if (storedHash == null) {
            System.out.println("登录失败: 用户 " + username + " 不存在");
            return;
        }
        boolean ok = verifyPassword(plainPassword, storedHash);
        System.out.println("登录" + (ok ? "成功" : "失败") + ": " + username);
    }

    /** 登录并静默升级密码哈希 cost（常见安全实践） */
    private static void loginWithUpgrade(String username, String plainPassword) {
        String storedHash = userDB.get(username);
        if (storedHash == null) {
            System.out.println("登录失败: 用户 " + username + " 不存在");
            return;
        }
        if (!verifyPassword(plainPassword, storedHash)) {
            System.out.println("登录失败: " + username + " 密码错误");
            return;
        }
        System.out.println("登录成功: " + username);

        // 验证通过后，检查是否需要升级哈希强度
        if (needsUpgrade(storedHash, DEFAULT_COST)) {
            String newHash = hashPassword(plainPassword);
            userDB.put(username, newHash);
            System.out.println("密码哈希已从 cost=10 升级到 cost=" + DEFAULT_COST);
        }
    }
}
