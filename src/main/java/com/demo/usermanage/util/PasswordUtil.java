package com.demo.usermanage.util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * 密码工具类：项目内所有密码的哈希与校验统一走这里，避免各处重复实现、成本因子不一致。
 *
 * 为什么用 BCrypt 而不是 MD5/SHA：
 *   MD5/SHA 是「快哈希」，GPU 每秒可枚举数十亿次，密码短且熵低时极易被暴力破解；
 *   BCrypt 是「慢哈希」，内置成本因子（迭代 2^cost 次）刻意拖慢计算，
 *   且每次加密自动生成随机盐并编码进结果串，同一密码两次加密结果也不同，
 *   可有效抵抗暴力破解与彩虹表。
 *
 * 哈希串格式（盐与 cost 已编码其中，校验时无需额外传入）：
 *   $2a$12$<22位盐><31位摘要>
 */
public final class PasswordUtil {

    /** 默认成本因子：每 +1 计算耗时翻倍，12 为安全与性能的常用平衡点 */
    private static final int DEFAULT_COST = 12;

    /** 工具类禁止实例化 */
    private PasswordUtil() {
    }

    /**
     * 明文密码 -> BCrypt 哈希（使用默认成本因子）。
     *
     * @param rawPassword 明文密码，不可为空
     * @return 60 位哈希串，可直接入库
     */
    public static String encrypt(String rawPassword) {
        return encrypt(rawPassword, DEFAULT_COST);
    }

    /**
     * 明文密码 -> BCrypt 哈希（指定成本因子）。
     *
     * @param rawPassword 明文密码，不可为空
     * @param cost        成本因子，取值 4~31
     */
    public static String encrypt(String rawPassword, int cost) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("待加密密码不能为空");
        }
        if (cost < 4 || cost > 31) {
            throw new IllegalArgumentException("BCrypt 成本因子需在 4~31 之间");
        }
        // gensalt 每次生成新的随机盐，故同一密码多次加密结果不同
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(cost));
    }

    /**
     * 校验明文密码与哈希串是否匹配（登录场景使用）。
     * 从哈希串中提取盐与 cost 后重新计算比对，因此无需保存盐。
     *
     * @param rawPassword 用户输入的明文密码
     * @param hashed      库中存储的哈希串
     * @return 匹配返回 true；哈希为 null/空或格式非法一律返回 false
     */
    public static boolean verify(String rawPassword, String hashed) {
        if (rawPassword == null || rawPassword.isEmpty() || hashed == null || hashed.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, hashed);
        } catch (IllegalArgumentException e) {
            // 哈希串非 BCrypt 格式（被篡改或为旧算法产物），视为校验不通过
            return false;
        }
    }
}