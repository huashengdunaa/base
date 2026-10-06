package com.demo.usermanage.mapper;

import com.demo.usermanage.entity.User;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * UserMapper 的内存伪数据实现：用 ConcurrentHashMap 模拟 t_user 表，
 * 方法语义与 MyBatis 注解 SQL 严格保持一致（含 status=-1 逻辑删除过滤），
 * 免安装 MySQL 即可运行全部接口。
 */
@Repository
public class InMemoryUserMapper implements UserMapper {

    /** 模拟数据库表：主键 -> 用户行 */
    private final ConcurrentMap<Long, User> table = new ConcurrentHashMap<>();

    /** 模拟自增主键 */
    private final AtomicLong idGenerator = new AtomicLong(0);

    /** 启动时灌入 3 条伪数据，密码以 BCrypt 哈希形式“入库”，不存明文 */
    @PostConstruct
    public void initFakeData() {
        insertFakeUser("admin", "admin123", "admin@demo.com", 1);
        insertFakeUser("zhangsan", "zs123456", "zhangsan@demo.com", 1);
        insertFakeUser("lisi", "lisi123456", "lisi@demo.com", 0);
    }

    private void insertFakeUser(String username, String rawPassword, String email, int status) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt(10)));
        u.setEmail(email);
        u.setStatus(status);
        u.setCreatedAt(LocalDateTime.now());
        insert(u);
    }

    @Override
    public int insert(User user) {
        // 模拟自增主键回填
        if (user.getId() == null) {
            user.setId(idGenerator.incrementAndGet());
        } else {
            idGenerator.accumulateAndGet(user.getId(), Math::max);
        }
        table.put(user.getId(), user);
        return 1;
    }

    @Override
    public User selectById(Long id) {
        User u = table.get(id);
        return (u != null && u.getStatus() != -1) ? copyOf(u) : null;
    }

    @Override
    public User selectByUsername(String username) {
        return table.values().stream()
                .filter(u -> u.getStatus() != -1 && u.getUsername().equals(username))
                .findFirst()
                .map(InMemoryUserMapper::copyOf)
                .orElse(null);
    }

    @Override
    public List<User> selectPage(long offset, long size) {
        return table.values().stream()
                .filter(u -> u.getStatus() != -1)
                .sorted(Comparator.comparing(User::getId))
                .skip(offset)
                .limit(size)
                .map(InMemoryUserMapper::copyOf)
                .collect(Collectors.toList());
    }

    @Override
    public long countActive() {
        return table.values().stream()
                .filter(u -> u.getStatus() != -1)
                .count();
    }

    @Override
    public int updateDynamic(User param) {
        User stored = table.get(param.getId());
        if (stored == null || stored.getStatus() == -1) {
            // 与 SQL 的 WHERE 条件一致：行不存在或已删除则影响 0 行
            return 0;
        }
        // 仅覆盖非 null 字段，与 <set><if> 动态 SQL 语义一致
        if (param.getUsername() != null) {
            stored.setUsername(param.getUsername());
        }
        if (param.getPassword() != null) {
            stored.setPassword(param.getPassword());
        }
        if (param.getEmail() != null) {
            stored.setEmail(param.getEmail());
        }
        if (param.getStatus() != null) {
            stored.setStatus(param.getStatus());
        }
        return 1;
    }

    @Override
    public int logicDelete(Long id) {
        User stored = table.get(id);
        if (stored == null || stored.getStatus() == -1) {
            return 0;
        }
        stored.setStatus(-1);
        return 1;
    }

    /** 返回副本，模拟 MyBatis 每次查询新建对象的行为，避免外部直接改“表”里的行 */
    private static User copyOf(User src) {
        User u = new User();
        u.setId(src.getId());
        u.setUsername(src.getUsername());
        u.setPassword(src.getPassword());
        u.setEmail(src.getEmail());
        u.setStatus(src.getStatus());
        u.setCreatedAt(src.getCreatedAt());
        return u;
    }
}
