package com.demo.jwt.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：生成、解析、校验。
 *
 * Token 结构（HS256）：
 *   header.payload.signature
 *   payload 中包含：sub(用户ID)、username、iat(签发时间)、exp(过期时间)
 *
 * 说明：
 *   - 密钥此处硬编码仅用于 Demo；生产环境应从配置中心/环境变量读取。
 *   - HS256 要求密钥长度 ≥ 256 bit（32 字节）。
 */
public class JwtUtil {

    private static final String SECRET = "DemoJwtHmacShaSecretKey2026ForLearningProject!!"; // 53 字节
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    /** 过期时间 2 小时 */
    private static final long EXPIRATION_MS = 2 * 60 * 60 * 1000L;

    /** 生成 JWT（登录成功后调用） */
    public static String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRATION_MS);
        return Jwts.builder()
                .setSubject(String.valueOf(userId))   // sub = userId
                .claim("username", username)          // 自定义声明
                .setIssuedAt(now)                     // iat
                .setExpiration(expiry)                 // exp
                .signWith(KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    /** 解析并验证签名/有效期；非法或过期会抛 JwtException */
    public static Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(KEY)
                .build()
                .parseClaimsJws(token)   // 验签 + 校验 exp
                .getBody();
    }

    public static long getExpirationSeconds() {
        return EXPIRATION_MS / 1000;
    }
}
