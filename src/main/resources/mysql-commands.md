# MySQL 常用命令学习文档（Java 初级开发向）

基于 Docker 容器 `mysql-learning`（镜像 mysql:8.0，账号 root/123456，库名 demo）。

---

## 一、连接与退出

```bash
# 进入容器内的 mysql 客户端
docker exec -it mysql-learning mysql -uroot -p123456

# 指定库连接（推荐，进去后不用再 USE）
docker exec -it mysql-learning mysql -uroot -p123456 demo

# 只执行一条 SQL 后退出
docker exec -it mysql-learning mysql -uroot -p123456 -e "SHOW DATABASES;"
```

客户端内：

```sql
EXIT;                 -- 退出（QUIT 或 \q 同义）
STATUS;               -- 查看连接信息（版本、当前库、字符集）
```

语句以 `;` 结尾，`--` 后加空格为单行注释。

---

## 二、库与表结构管理

```sql
SHOW DATABASES;                       -- 所有库
CREATE DATABASE demo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE demo;                             -- 切换当前库
SELECT DATABASE();                    -- 查看当前所在库
DROP DATABASE demo;                   -- 删库（慎用）

SHOW TABLES;                          -- 当前库所有表
DESC user;                            -- 查看表结构（字段/类型/可空/键/默认值）
SHOW CREATE TABLE user\G              -- 查看完整建表语句（\G 竖排显示）
```

### 建表（对照项目用户表）

```sql
CREATE TABLE user (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  username    VARCHAR(50)  NOT NULL,
  email       VARCHAR(100) DEFAULT NULL,
  password    VARCHAR(100) NOT NULL,
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1逻辑删除',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

```sql
ALTER TABLE user ADD COLUMN phone VARCHAR(20) AFTER email;  -- 加字段
ALTER TABLE user MODIFY COLUMN username VARCHAR(80) NOT NULL; -- 改字段类型
ALTER TABLE user DROP COLUMN phone;                         -- 删字段
ALTER TABLE user RENAME TO t_user;                          -- 改表名
TRUNCATE TABLE user;        -- 清空整表（自增重置，不可回滚，慎用）
DROP TABLE user;            -- 删表
```

---

## 三、数据增删改查（核心）

### 查询

```sql
SELECT * FROM user WHERE id = 1;
SELECT id, username, email FROM user WHERE deleted = 0;

-- 条件
SELECT * FROM user WHERE username LIKE 'a%' AND deleted = 0;
SELECT * FROM user WHERE id IN (1, 2, 3);
SELECT * FROM user WHERE id BETWEEN 1 AND 10;
SELECT * FROM user WHERE email IS NULL;

-- 排序与分页
SELECT * FROM user ORDER BY id DESC LIMIT 10;
SELECT * FROM user ORDER BY id ASC LIMIT 20, 10;   -- 跳过 20 条取 10 条（第 3 页，每页 10）

-- 去重与计数
SELECT DISTINCT username FROM user;
SELECT COUNT(*) FROM user WHERE deleted = 0;
```

### 新增

```sql
INSERT INTO user (username, email, password) VALUES ('alice', 'alice@demo.com', 'hashed-pwd');
INSERT INTO user (username, email, password)
VALUES ('bob','bob@demo.com','x'), ('carol','carol@demo.com','y');   -- 批量插入
```

### 修改

```sql
UPDATE user SET email = 'new@demo.com' WHERE id = 1;
-- 必须带 WHERE！无 WHERE 会更新全表
```

### 删除

```sql
DELETE FROM user WHERE id = 1;       -- 物理删除
UPDATE user SET deleted = 1 WHERE id = 1;   -- 项目实际使用的逻辑删除
```

> 安全习惯：执行 UPDATE/DELETE 前先把 WHERE 条件放到 SELECT 里跑一遍确认影响范围。

---

## 四、聚合与分组

```sql
SELECT COUNT(*), MAX(id), MIN(id), AVG(id) FROM user;

SELECT deleted, COUNT(*) FROM user GROUP BY deleted;

SELECT deleted, COUNT(*) AS cnt
FROM user
GROUP BY deleted
HAVING cnt > 1;       -- 分组后过滤用 HAVING，WHERE 用于分组前行过滤
```

---

## 五、多表连接（必会基础）

```sql
-- orders(oid, user_id, amount)
SELECT o.oid, o.amount, u.username
FROM orders o
INNER JOIN user u ON o.user_id = u.id
WHERE o.amount > 100;

-- LEFT JOIN：左表全保留，右表无匹配时列为 NULL
SELECT u.username, COUNT(o.oid) AS order_count
FROM user u
LEFT JOIN orders o ON o.user_id = u.id
GROUP BY u.username;
```

| 类型 | 结果 |
|---|---|
| INNER JOIN | 只返回两表匹配上的行 |
| LEFT JOIN | 左表全部 + 右表匹配行 |
| RIGHT JOIN | 右表全部 + 左表匹配行（初级阶段改写为 LEFT JOIN 即可） |

---

## 六、索引

```sql
CREATE INDEX idx_user_email ON user(email);              -- 普通索引
CREATE UNIQUE INDEX uk_username ON user(username);       -- 唯一索引
SHOW INDEX FROM user;                                    -- 查看表上的索引
DROP INDEX idx_user_email ON user;
```

### 执行计划（慢查询排查第一步）

```sql
EXPLAIN SELECT * FROM user WHERE email = 'a@demo.com';
```

重点看两列：

| 列 | 关注点 |
|---|---|
| type | `ALL` 全表扫描需警惕；`ref`/`const` 走索引较好 |
| key | 实际命中的索引，NULL 表示没走索引 |
| rows | 预估扫描行数，越小越好 |

---

## 七、事务

```sql
START TRANSACTION;
UPDATE account SET balance = balance - 100 WHERE id = 1;
UPDATE account SET balance = balance + 100 WHERE id = 2;
COMMIT;          -- 都成功才提交
-- ROLLBACK;     -- 任一步失败则回滚
```

默认 autocommit 开启，单条语句自动提交。Spring 的 `@Transactional` 底层就是这些。

---

## 八、用户与权限（了解）

```sql
CREATE USER 'appuser'@'%' IDENTIFIED BY 'App@123456';
GRANT SELECT, INSERT, UPDATE, DELETE ON demo.* TO 'appuser'@'%';
FLUSH PRIVILEGES;
SHOW GRANTS FOR 'appuser'@'%';
REVOKE DELETE ON demo.* FROM 'appuser'@'%';
DROP USER 'appuser'@'%';
```

`'appuser'@'%'` 允许任意主机连接；`'appuser'@'localhost'` 只允许本机。容器化连接应用通常需要 `%`。

---

## 九、实用运维命令

```sql
SHOW PROCESSLIST;              -- 当前所有连接和正在执行的语句
KILL 连接ID;                   -- 杀掉长时间占用的连接
SHOW VARIABLES LIKE 'max_connections';
SHOW STATUS LIKE 'Threads_connected';
```

### 命令行导出导入（宿主机执行，容器内也可用 mysqldump）

```bash
# 导出
mkdir -p ~/backup
docker exec mysql-learning mysqldump -uroot -p123456 demo > ~/backup/demo.sql

# 导入（先保证库存在）
docker exec -i mysql-learning mysql -uroot -p123456 demo < ~/backup/demo.sql
```

---

## 十、Java 开发高频排查场景

| 场景 | 命令 |
|---|---|
| 接口数据不对，直接查原始数据 | `SELECT * FROM user WHERE id = ?;` |
| MyBatis 语句没走索引 | `EXPLAIN` 看 key 和 rows |
| 怀疑连错库 | `SELECT DATABASE();` + `STATUS;` |
| 看表字符集是否 utf8mb4 | `SHOW CREATE TABLE user\G` |
| 连接池报 Too many connections | `SHOW PROCESSLIST;`、`SHOW STATUS LIKE 'Threads_connected';` |
| 数据被逻辑删除查不到 | 检查 `deleted` 字段值，查询带不带 `deleted = 0` |

---

## 十一、注意事项

- MySQL 8 默认字符集 utf8mb4，建库建表统一使用，避免 emoji 乱码。
- 生产环境禁止无 WHERE 的 UPDATE/DELETE，客户端可开启 `sql_safe_updates`。
- 密码在命令行 `-p123456` 会有明文警告，学习环境可忽略；正式环境交互输入 `-p` 后回车。
- 时间类型用 DATETIME，项目中 LocalDateTime 由 MyBatis/Hibernate 自动转换。
