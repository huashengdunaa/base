package com.demo.bcrypt;

import org.mindrot.jbcrypt.BCrypt;

/**
 * 01 BCrypt 基础用法：密码哈希生成与验证
 *
 * BCrypt 核心特点：
 *   1. 自带盐（salt）：每次哈希结果不同，无需单独存储盐
 *   2. 抗彩虹表：盐内嵌在哈希串中，格式: $2a$[cost]$[22位盐][31位哈希]
 *   3. 可调强度（cost factor）：默认 10，每 +1 计算量翻倍
 *   4. 单向不可逆：只能通过 checkPwd 验证，无法从哈希反推原文
 *
 * 对比 MD5/SHA：
 *   - MD5/SHA 是快速哈希，GPU 暴力破解效率极高，不适合密码存储
 *   - BCrypt 是慢哈希（故意慢），有效抵抗暴力破解
 */
public class Demo01_BCryptBasic {

    public static void main(String[] args) {
        String password = "MySecurePass123!";

        // ---- 1. 生成哈希（使用默认 cost=10） ----
        // 每次调用结果不同（因为盐是随机生成的）
        String hash1 = BCrypt.hashpw(password, BCrypt.gensalt());
        String hash2 = BCrypt.hashpw(password, BCrypt.gensalt());

        System.out.println("原始密码: " + password);
        System.out.println("第一次哈希: " + hash1);
        System.out.println("第二次哈希: " + hash2);
        System.out.println("两次哈希不同（盐不同）: " + !hash1.equals(hash2));

        // ---- 2. 验证密码 ----
        // BCrypt.checkpw 会从哈希串中提取盐和 cost，重新计算后比对
        System.out.println("\n正确密码验证: " + BCrypt.checkpw(password, hash1));   // true
        System.out.println("错误密码验证: " + BCrypt.checkpw("wrongPwd", hash1));   // false

        // ---- 3. 指定 cost factor 生成哈希 ----
        // cost=12：安全性更高，但耗时也翻倍（相对 cost=10）
        String hashWithCost12 = BCrypt.hashpw(password, BCrypt.gensalt(12));
        System.out.println("\ncost=12 的哈希: " + hashWithCost12);
        System.out.println("cost=12 验证: " + BCrypt.checkpw(password, hashWithCost12)); // true

        // ---- 4. 哈希串结构解析 ----
        // 格式: $2a$10$N9qo8uLOickgx2ZMRZoMy...（共60字符）
        //   $2a$  — BCrypt 版本标识
        //   10    — cost factor（计算轮数 = 2^10）
        //   N9qo8uLOickgx2ZMRZoMyeI — 22 字符的盐（Base64 编码）
        //   剩余 31 字符 — 哈希值
        System.out.println("\n哈希串结构:");
        System.out.println("  版本:  " + hash1.substring(0, 3));   // $2a
        System.out.println("  cost: " + hash1.substring(4, 6));   // 10
        System.out.println("  盐:   " + hash1.substring(7, 29));  // 22 chars
        System.out.println("  哈希: " + hash1.substring(29));    // 31 chars
    }
}
