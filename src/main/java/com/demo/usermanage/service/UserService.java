package com.demo.usermanage.service;

import com.demo.usermanage.cache.UserCacheService;
import com.demo.usermanage.common.BusinessException;
import com.demo.usermanage.common.PageResult;
import com.demo.usermanage.dto.UserCreateRequest;
import com.demo.usermanage.dto.UserUpdateRequest;
import com.demo.usermanage.entity.User;
import com.demo.usermanage.mapper.UserMapper;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户业务层：负责查重、密码 BCrypt 哈希、动态更新组装与逻辑删除，
 * 数据访问统一走 UserMapper（当前为内存实现，可无缝切换 MySQL）。
 *
 * 缓存策略：
 * - getById 走 Redis 缓存（user:{id}，TTL 30 分钟，互斥锁防击穿）
 * - update 采用延迟双删保证缓存一致性
 * - 逻辑删除后删除缓存，避免缓存继续返回已删除用户
 */
@Service
public class UserService {

    /** BCrypt 成本因子，与 bcrypt 模块 Demo 保持一致 */
    private static final int BCRYPT_COST = 12;

    private final UserMapper userMapper;
    private final UserCacheService userCacheService;

    /** 延迟双删的延迟时间（毫秒），默认 500ms */
    private final long doubleDeleteDelayMs;

    public UserService(UserMapper userMapper,
                       UserCacheService userCacheService,
                       @Value("${user.cache.double-delete-delay-ms:500}") long doubleDeleteDelayMs) {
        this.userMapper = userMapper;
        this.userCacheService = userCacheService;
        this.doubleDeleteDelayMs = doubleDeleteDelayMs;
    }

    /**
     * 创建用户：
     * 1. 用户名查重（同用户名未删除用户已存在则拒绝）
     * 2. 明文密码 BCrypt 哈希后再入库
     * 新用户此前没有缓存 key，无需删缓存；首次查询时回源自动回填
     */
    public User create(UserCreateRequest req) {
        if (userMapper.selectByUsername(req.getUsername()) != null) {
            throw new BusinessException(400, "用户名已存在");
        }

        User user = new User();
        user.setUsername(req.getUsername().trim());
        user.setPassword(hash(req.getPassword()));
        user.setEmail(req.getEmail().trim());
        user.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        user.setCreatedAt(LocalDateTime.now());

        userMapper.insert(user);
        // insert 后 id 已回填，走缓存服务读取（顺带回填缓存）
        return getById(user.getId());
    }

    /** 按 id 查询用户：先查 Redis，未命中回源；不存在（含已逻辑删除）抛 404 */
    public User getById(Long id) {
        User user = userCacheService.getUserById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    /** 分页查询：page 从 1 开始（列表不做缓存，仅单条详情缓存） */
    public PageResult<User> page(int page, int size) {
        long total = userMapper.countActive();
        long offset = (long) (page - 1) * size;
        List<User> records = userMapper.selectPage(offset, size);
        return new PageResult<>(page, size, total, records);
    }

    /**
     * 更新用户（局部更新，只处理传入的非 null 字段），使用延迟双删保证一致性：
     *   第一次删缓存 → 更新数据库 → 延迟一小段时间 → 第二次删缓存
     * 第二次删除用于清掉“更新期间被其他读请求回填的旧值”。
     * 注意：存在性校验与更新后读取都直接走 Mapper，避免在双删窗口内把旧值写回缓存。
     */
    public User update(Long id, UserUpdateRequest req) {
        // 直查 Mapper 确认用户存在（不走缓存，避免回填）
        if (userMapper.selectById(id) == null) {
            throw new BusinessException(404, "用户不存在");
        }

        User patch = new User();
        patch.setId(id);

        if (req.getUsername() != null) {
            String newUsername = req.getUsername().trim();
            if (newUsername.isEmpty()) {
                throw new BusinessException(400, "用户名不能为空");
            }
            User sameName = userMapper.selectByUsername(newUsername);
            if (sameName != null && !sameName.getId().equals(id)) {
                throw new BusinessException(400, "用户名已存在");
            }
            patch.setUsername(newUsername);
        }

        if (req.getPassword() != null) {
            patch.setPassword(hash(req.getPassword()));
        }

        if (req.getEmail() != null) {
            patch.setEmail(req.getEmail().trim());
        }

        if (req.getStatus() != null) {
            patch.setStatus(req.getStatus());
        }

        // 第一次删除：防止更新期间命中旧缓存
        userCacheService.evict(id);

        int rows = userMapper.updateDynamic(patch);
        if (rows == 0) {
            throw new BusinessException(404, "用户不存在");
        }

        // 延迟：覆盖“读旧库 → 回填旧缓存”这类并发交错的时间窗口
        sleepQuietly(doubleDeleteDelayMs);

        // 第二次删除：清掉更新窗口内可能被回填的旧值
        userCacheService.evict(id);

        // 缓存此刻为空，返回最新数据；下一次 GET 会回源并回填新值
        return userMapper.selectById(id);
    }

    /** 逻辑删除：status 置 -1，数据行保留；不存在则 404；随后删除缓存 */
    public void deleteById(Long id) {
        // 先给出明确的 404 语义（走缓存也可，删除前缓存中可能存在）
        getById(id);
        userMapper.logicDelete(id);
        // 删除后必须失效缓存，否则缓存会在 TTL 内继续返回“已删除用户”
        userCacheService.evict(id);
    }

    /** 明文密码 -> BCrypt 哈希（自带随机盐） */
    private String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(BCRYPT_COST));
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
