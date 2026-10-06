package com.demo.usermanage.cache;

import com.demo.usermanage.entity.User;
import com.demo.usermanage.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 用户缓存服务（Cache-Aside 旁路缓存）。
 *
 * key 设计：
 *   user:{id}       用户数据缓存，TTL 30 分钟
 *   lock:user:{id}  重建缓存的互斥锁
 *
 * 缓存击穿防护（互斥锁方案）：
 *   热点 key 过期瞬间只允许一个线程查库重建缓存（SET NX EX 抢锁），
 *   其余线程自旋等待后重读缓存；抢锁后二次检查（DCL），避免重复回源。
 *   锁带随机 token，释放时用 Lua 脚本“比对 token 再删除”，防止误删别人的锁。
 *
 * 高可用：Redis 连接/超时故障时读路径立即降级为直接查库（不空等锁），
 * 写缓存/删缓存失败只告警不影响主流程，保证 Redis 故障时接口可用。
 */
@Service
public class UserCacheService {

    private static final Logger log = LoggerFactory.getLogger(UserCacheService.class);

    private static final String KEY_PREFIX = "user:";
    private static final String LOCK_PREFIX = "lock:user:";

    /** 缓存过期时间：30 分钟 */
    private final Duration ttl;

    /** 锁的过期时间：防止持锁线程宕机导致死锁 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    /** 等锁最大次数与间隔：50 × 100ms = 最多等 5 秒 */
    private static final int LOCK_RETRY_TIMES = 50;
    private static final long LOCK_RETRY_SLEEP_MS = 100L;

    /** 释放锁的 Lua 脚本：只删自己加的锁（GET 比对 token 一致才 DEL） */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end",
            Long.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserMapper userMapper;

    public UserCacheService(RedisTemplate<String, Object> redisTemplate,
                            UserMapper userMapper,
                            @Value("${user.cache.ttl-minutes:30}") long ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.userMapper = userMapper;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * 按 id 查用户：先读缓存，未命中则互斥回源重建。
     * Redis 整体不可用时立即降级直查库（不参与等锁自旋，避免无谓等待）。
     *
     * @return 用户；不存在返回 null（不存在的数据不缓存，避免无意义占用）
     */
    public User getUserById(Long id) {
        try {
            return doGetWithMutex(id);
        } catch (RedisUnavailableException e) {
            // Redis 故障：一条告警，立刻直查数据库
            log.warn("[缓存] Redis 不可用，本次请求降级直查数据库: id={}", id);
            return userMapper.selectById(id);
        }
    }

    private User doGetWithMutex(Long id) {
        String key = buildKey(id);

        // 1. 先读缓存
        User cached = cacheGet(key);
        if (cached != null) {
            return cached;
        }

        // 2. 缓存未命中：自旋抢互斥锁
        String lockKey = LOCK_PREFIX + id;
        String token = UUID.randomUUID().toString();

        for (int i = 0; i < LOCK_RETRY_TIMES; i++) {
            Boolean locked = tryLock(lockKey, token);
            if (Boolean.TRUE.equals(locked)) {
                try {
                    // 3. DCL：拿到锁后再查一次缓存（可能已被前一个持锁线程重建好）
                    cached = cacheGet(key);
                    if (cached != null) {
                        return cached;
                    }

                    // 4. 持锁线程独占回源查库并重建缓存
                    User dbUser = userMapper.selectById(id);
                    if (dbUser != null) {
                        cacheSet(key, dbUser);
                    }
                    return dbUser;
                } finally {
                    unlock(lockKey, token);
                }
            }

            // 5. 没抢到锁：稍等后重读缓存（重建通常很快完成）
            sleepQuietly(LOCK_RETRY_SLEEP_MS);
            cached = cacheGet(key);
            if (cached != null) {
                return cached;
            }
        }

        // 6. 等锁超时：放弃等待，直接查库降级（宁可重复查库，不能让请求挂死）
        log.warn("[缓存] 等待重建锁超时，降级直查数据库: {}", lockKey);
        return userMapper.selectById(id);
    }

    /** 删除缓存（更新/删除用户时调用），Redis 异常不影响主流程 */
    public void evict(Long id) {
        try {
            redisTemplate.delete(buildKey(id));
        } catch (DataAccessException e) {
            log.warn("[缓存] 删除失败，已忽略: key={}, 原因={}", buildKey(id), e.getMessage());
        }
    }

    // ====== Redis 原子操作（均做降级容错） ======

    /**
     * 读缓存。Redis 连接/超时类故障抛 {@link RedisUnavailableException}，
     * 由调用方立即降级，区别于“key 不存在”的正常未命中（返回 null）。
     */
    private User cacheGet(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            return value instanceof User ? (User) value : null;
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(e);
        }
    }

    private void cacheSet(String key, User user) {
        try {
            redisTemplate.opsForValue().set(key, user, ttl);
        } catch (DataAccessException e) {
            log.warn("[缓存] 写入失败，已忽略: key={}, 原因={}", key, e.getMessage());
        }
    }

    /**
     * SET lockKey token NX EX 10：仅当 key 不存在时设置成功并带过期时间。
     * Redis 故障同样立即抛 {@link RedisUnavailableException}（不能按“锁被占用”处理，
     * 否则所有请求会空等一个锁的自旋周期）。
     */
    private Boolean tryLock(String lockKey, String token) {
        try {
            return redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, LOCK_TTL);
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(e);
        }
    }

    /** Lua 原子释放锁，避免 DEL 掉其他线程持有的锁 */
    private void unlock(String lockKey, String token) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (DataAccessException e) {
            // 锁有 TTL 兜底，释放失败最多等其自然过期
            log.warn("[缓存] 释放锁失败，等待 TTL 自动过期: key={}", lockKey);
        }
    }

    private String buildKey(Long id) {
        return KEY_PREFIX + id;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Redis 不可用信号：连接被拒、超时等，触发直查库降级 */
    private static class RedisUnavailableException extends RuntimeException {
        RedisUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
