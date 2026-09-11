package com.demo.bcrypt;

import org.mindrot.jbcrypt.BCrypt;

/**
 * 02 BCrypt Cost Factor 强度对比
 *
 * cost factor 决定计算轮数 = 2^cost：
 *   cost=10  → 2^10  = 1,024 轮   （默认，约 50-100ms）
 *   cost=12  → 2^12  = 4,096 轮   （推荐生产环境，约 200-400ms）
 *   cost=14  → 2^14  = 16,384 轮  （高安全场景，约 1-2s）
 *   cost=16  → 2^16  = 65,536 轮  （极安全但慢，约 5-8s）
 *
 * 选型建议：
 *   - 登录场景：cost=12（安全与体验的平衡点）
 *   - 管理后台：cost=14
 *   - 选原则：用户可容忍的延迟内取最大值，每 2 年 +1 适应硬件升级
 */
public class Demo02_BCryptCostFactor {

    public static void main(String[] args) {
        String password = "TestPass#2026";

        int[] costs = {6, 8, 10, 12, 14};

        System.out.println("原始密码: " + password);
        System.out.println("==============================================");
        System.out.printf("%-6s %-10s %-15s%n", "cost", "耗时(ms)", "哈希(前30字符)");
        System.out.println("==============================================");

        for (int cost : costs) {
            long start = System.currentTimeMillis();
            String hash = BCrypt.hashpw(password, BCrypt.gensalt(cost));
            long elapsed = System.currentTimeMillis() - start;

            // 验证也需同样耗时
            long verifyStart = System.currentTimeMillis();
            boolean match = BCrypt.checkpw(password, hash);
            long verifyElapsed = System.currentTimeMillis() - verifyStart;

            System.out.printf("%-6d %-10d %-15s%n", cost, elapsed, hash.substring(0, 30) + "...");
            System.out.printf("       验证耗时: %dms, 验证结果: %b%n", verifyElapsed, match);
        }

        System.out.println("==============================================");
        System.out.println("结论: cost 每增加 1，耗时约翻倍");
        System.out.println("建议: 生产环境使用 cost=12（约 200-400ms，安全且用户可接受）");
    }
}
