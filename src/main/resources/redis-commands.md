# Redis 常用命令学习文档（Java 初级开发向）

基于 Docker 容器 `redis-learning`（镜像 redis:7）。覆盖本地开发与日常排查 90% 的命令。

---

## 一、连接与退出

```bash
# 进入容器内的交互式客户端（无需密码时）
docker exec -it redis-learning redis-cli

# 容器设了密码时
docker exec -it redis-learning redis-cli -a 密码

# 指定库（默认 0 号库，共 16 个）
docker exec -it redis-learning redis-cli -n 1

# 执行单条命令后退出（脚本/排查用）
docker exec -it redis-learning redis-cli PING
```

客户端内：

```redis
127.0.0.1:6379> PING          # 返回 PONG 表示连通
127.0.0.1:6379> AUTH 密码      # 连接后再认证
127.0.0.1:6379> SELECT 1      # 切换到 1 号库
127.0.0.1:6379> EXIT          # 退出（QUIT 同义）
```

> 命令不区分大小写，key 区分大小写。

---

## 二、Key 通用命令

```redis
KEYS *                 # 列出所有 key（生产禁用，阻塞主线程，学习环境可用）
KEYS user:*            # 按通配符查
SCAN 0 MATCH user:* COUNT 100   # 生产推荐：游标式分批扫描，不阻塞
EXISTS user:1          # key 是否存在，返回个数
TYPE user:1            # 查看 key 的数据类型
TTL user:1             # 查看剩余过期秒数；-1 永不过期；-2 不存在
EXPIRE user:1 1800     # 设置 1800 秒后过期
PERSIST user:1         # 移除过期时间
DEL user:1             # 删除（同步，大 key 慎用）
UNLINK user:1          # 删除（异步释放内存，大 key 推荐）
RENAME old new         # 重命名
```

项目约定：用户缓存 key 为 `user:{id}`，锁 key 为 `lock:user:{id}`，排查时直接查这两类。

---

## 三、五种基础数据类型

### 1. String（项目缓存用）

```redis
SET user:1 '{"id":1,"name":"alice"}'   # 写入
GET user:1                              # 读取
SETEX code:1001 300 "846219"            # 写入并设 300 秒过期
SETNX lock:user:1 "token-abc"          # 不存在才写入（分布式锁基础），成功返回 1
SET lock:user:1 "token-abc" NX EX 10   # 等价：不存在才设置 + 10 秒过期（原子）
INCR article:100:views                  # 计数 +1（不存在则从 0 开始）
INCRBY stock:2001 -1                    # 减 1
MSET k1 v1 k2 v2                        # 批量写
MGET k1 k2                              # 批量读
```

### 2. Hash（缓存对象字段）

```redis
HSET user:1 name alice age 20
HGET user:1 name
HGETALL user:1            # 取全部字段
HMGET user:1 name age
HDEL user:1 age
HINCRBY user:1 age 1
HEXISTS user:1 name
```

### 3. List（简单队列）

```redis
LPUSH task:queue "t1" "t2"   # 左侧入队
RPOP task:queue              # 右侧出队（FIFO）
LRANGE task:queue 0 -1       # 查看全部（0 到 -1 表示全部）
LLEN task:queue
LINDEX task:queue 0
```

### 4. Set（去重、标签）

```redis
SADD tags:article:1 java redis mysql
SMEMBERS tags:article:1
SISMEMBER tags:article:1 java
SREM tags:article:1 mysql
SINTER tags:article:1 tags:article:2   # 交集（共同标签）
SUNION tags:article:1 tags:article:2   # 并集
SCARD tags:article:1                    # 元素数
```

### 5. ZSet（有序集合，排行榜）

```redis
ZADD rank 90 alice 85 bob 95 carol
ZRANGE rank 0 -1 WITHSCORES          # 按分数升序
ZREVRANGE rank 0 2 WITHSCORES        # 前三名（降序）
ZRANK rank alice                     # 升序排名
ZREVRANK rank alice                  # 降序排名
ZSCORE rank alice
ZINCRBY rank 5 alice                 # 加分
ZCARD rank
```

---

## 四、订阅发布（了解）

```redis
# 客户端 A：订阅频道（会进入阻塞等待状态）
SUBSCRIBE news

# 客户端 B：发消息
PUBLISH news "hello"
```

消息不持久化，离线的订阅者收不到。

---

## 五、库与数据库管理

```redis
DBSIZE                 # 当前库 key 数量
FLUSHDB                # 清空当前库（慎用）
FLUSHALL               # 清空所有库（禁用）
INFO                   # 服务器整体信息
INFO memory            # 只看内存信息：used_memory_human、mem_fragmentation_ratio
INFO replication       # 主从信息
CLIENT LIST            # 当前连接
CONFIG GET maxmemory   # 查看配置项
CONFIG GET save        # 查看持久化策略
```

---

## 六、持久化命令（了解）

```redis
SAVE                   # 主线程生成 RDB 快照（会阻塞，生产别用）
BGSAVE                 # fork 子进程生成 RDB 快照
LASTSAVE               # 上次快照时间
```

容器以 `--appendonly yes` 启动时开启 AOF，无需手工操作。

---

## 七、Java 开发高频排查场景

| 场景 | 命令 |
|---|---|
| 查某用户缓存内容 | `GET user:1` |
| 确认缓存是否已过期 | `TTL user:1` |
| 手动删缓存模拟未命中 | `DEL user:1` |
| 看锁是否被持有、值是不是自己的 token | `GET lock:user:1` |
| 模拟锁超时释放 | 等待，或 `DEL lock:user:1`（注意 Lua 原子释放逻辑） |
| 统计缓存了多少用户 | `SCAN 0 MATCH user:* COUNT 100` 配合 `DBSIZE` 判断 |
| 看 Redis 内存是否异常 | `INFO memory` |

---

## 八、注意事项

- `KEYS *`、`FLUSHALL`、大 key 的 `DEL`/`HGETALL` 会阻塞单线程，生产禁用或改异步命令。
- 多环境（本地/测试/生产）通过 `-h host -p port -a password` 连接，操作前先确认环境。
- 单个 String value 建议小于 10KB，集合类元素数建议控制在 5000 以内。
