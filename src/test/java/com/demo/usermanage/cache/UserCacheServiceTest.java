package com.demo.usermanage.cache;

import com.demo.usermanage.entity.User;
import com.demo.usermanage.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户缓存服务单元测试。
 *
 * 用 Mockito 模拟 RedisTemplate / ValueOperations / UserMapper，
 * 无需启动 Spring 容器、无需真实 Redis，验证：
 *   1. 缓存未命中时回源查库、抢互斥锁、按 30 分钟 TTL 回填缓存；
 *   2. 缓存命中时直接返回缓存对象，不再查询数据库（核心考察点）。
 */
@ExtendWith(MockitoExtension.class)
class UserCacheServiceTest {

    private static final Long USER_ID = 1L;
    private static final String CACHE_KEY = "user:1";
    private static final String LOCK_KEY = "lock:user:1";

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private UserMapper userMapper;

    /** 被测服务（ttlMinutes 在 setUp 中手动传入 30，与生产配置一致） */
    private UserCacheService userCacheService;

    /** 构造器第三个参数 ttlMinutes 无法靠 @InjectMocks 注入常量，手动构造 */
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        userCacheService = new UserCacheService(redisTemplate, userMapper, 30L);
    }

    private User buildUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("admin");
        user.setEmail("admin@demo.com");
        user.setStatus(1);
        return user;
    }

    @Test
    @DisplayName("缓存未命中：抢锁回源查库，并按 30 分钟 TTL 写入缓存")
    void cacheMiss_shouldLoadFromDbAndCacheWithTtl() {
        User dbUser = buildUser();

        // 两次读缓存均为 null：入口读一次 + 拿到锁后 DCL 再读一次
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        // 成功抢到互斥锁
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(Duration.ofSeconds(10))))
                .thenReturn(true);
        // 释放锁 Lua 脚本返回 1（删除成功）
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);
        // 数据库中存在该用户
        when(userMapper.selectById(USER_ID)).thenReturn(dbUser);

        User result = userCacheService.getUserById(USER_ID);

        assertSame(dbUser, result, "应返回数据库查出的对象");

        // 数据库只查了一次
        verify(userMapper, times(1)).selectById(USER_ID);

        // 缓存只回填一次，且 TTL 必须是 30 分钟
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations, times(1)).set(eq(CACHE_KEY), eq(dbUser), ttlCaptor.capture());
        assertEquals(Duration.ofMinutes(30), ttlCaptor.getValue(), "缓存 TTL 应为 30 分钟");

        // 释放了互斥锁
        verify(redisTemplate, times(1)).execute(any(DefaultRedisScript.class),
                eq(List.of(LOCK_KEY)), any());
    }

    @Test
    @DisplayName("缓存命中：直接返回缓存对象，不查库、不抢锁、不回填")
    void cacheHit_shouldReturnCachedUserWithoutQueryingDb() {
        User cachedUser = buildUser();
        when(valueOperations.get(CACHE_KEY)).thenReturn(cachedUser);

        // 连续两次查询同一用户
        User first = userCacheService.getUserById(USER_ID);
        User second = userCacheService.getUserById(USER_ID);

        assertSame(cachedUser, first);
        assertSame(cachedUser, second);

        // 核心断言：缓存命中后绝不访问数据库
        verify(userMapper, never()).selectById(any());
        // 不抢锁
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        // 不重复写缓存
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
        // 两次都走了缓存读取
        verify(valueOperations, times(2)).get(CACHE_KEY);
    }

    @Test
    @DisplayName("用户不存在：未命中且查库为 null 时不写缓存")
    void userNotFound_shouldNotCacheNull() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(Duration.ofSeconds(10))))
                .thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        User result = userCacheService.getUserById(USER_ID);

        assertEquals(null, result);
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }
}
