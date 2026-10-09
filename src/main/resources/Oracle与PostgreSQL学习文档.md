# Oracle 与 PostgreSQL 学习文档

> 适用对象：已有 MySQL 基础的开发者
> 学习目标：掌握 PostgreSQL 与 Oracle 的核心语法、语义差异、常见陷阱，具备从 MySQL 迁移与日常开发的能力
> 配套文件：`技术实践.txt.txt`（面试实战清单）

---

## 目录

- [零、总体认知：学习成本与路径](#零总体认知学习成本与路径)
- [一、环境准备（Docker 一条命令）](#一环境准备docker-一条命令)
- [二、PostgreSQL 篇](#二postgresql-篇)
- [三、Oracle 篇](#三oracle-篇)
- [四、三库横向对照表](#四三库横向对照表)
- [五、踩坑清单（迁移必看）](#五踩坑清单迁移必看)
- [六、实操练习](#六实操练习)
- [七、附录：常用命令速查](#七附录常用命令速查)

---

## 零、总体认知：学习成本与路径

### 结论先行

```
MySQL 基础（已有）
    │
    ├─→ PostgreSQL ：成本低，主要障碍是"从宽松变严格"
    │
    └─→ Oracle     ：SQL 语法眼熟，但语义陷阱多 + 体系庞大，成本中等偏高
```

关键认知：**MySQL 的 SQL 方言借鉴了不少 Oracle**，所以读 Oracle 代码会觉得似曾相识；而 PostgreSQL 更贴近标准 SQL，但类型校验严格，写惯 MySQL 的人会不适应。

### 难度对照

| 维度 | PostgreSQL | Oracle |
|------|-----------|--------|
| 上手难度 | 低 | 中 |
| 精通难度 | 中（调优生态相对轻） | 高（RAC / DataGuard / AWR） |
| SQL 相似度 | 中（标准 SQL） | 高（MySQL 大量借鉴 Oracle） |
| 最大障碍 | 类型严格、大小写折叠 | 空串 = NULL、需手动 COMMIT、体系庞大 |

### 推荐路径

1. **先学 PostgreSQL**，能复用约 80% 的 MySQL 知识，快速建立"标准 SQL + 严格类型"的直觉。
2. **再学 Oracle**，此时语法基本看得懂，重点补事务语义、PL/SQL、运维体系。
3. **最后看国产库**（达梦、金仓、openGauss、OceanBase），会发现基本是"Oracle 方言"或"PG 方言"，学一通百。

### 血缘关系图

```
                ├── PostgreSQL 系 ──→ 人大金仓 KingbaseES、瀚高 HighGo、
                │                      神通、openGauss / GaussDB
   开源三大系 ──┼── MySQL 系      ──→ TiDB、TDSQL、GoldenDB、GBase 8a
                │
                └── Oracle 系     ──→ 达梦 DM8（兼容 Oracle 语法）
                                     （OceanBase 自研内核，兼容 MySQL/Oracle）
```

---

## 一、环境准备（Docker 一条命令）

### PostgreSQL

```bash
docker run -d --name pg \
  -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=demo \
  postgres:16
```

```bash
# 进入命令行
docker exec -it pg psql -U postgres -d demo
```

### Oracle（XE 免费版）

```bash
docker run -d --name oracle \
  -p 1521:1521 -p 5500:5500 \
  -e ORACLE_PASSWORD=oracle \
  gvenzl/oracle-xe:21-slim
```

```bash
# 进入 sqlplus（容器名 + 服务名 XEPDB1）
docker exec -it oracle sqlplus system/oracle@//localhost:1521/XEPDB1
```

### 客户端工具

| 工具 | 支持 | 说明 |
|------|------|------|
| DBeaver | 全支持 | 免费，社区版够用，最推荐 |
| Navicat | 全支持 | 商业，UI 友好 |
| DataGrip | 全支持 | JetBrains 出品，与 IDEA 联动 |
| pgAdmin | PostgreSQL | PG 官方管理工具 |
| SQL Developer | Oracle | Oracle 官方免费工具 |

---

## 二、PostgreSQL 篇

### 2.1 定位与特点

- 起源于加州大学伯克利分校的 POSTGRES 项目，遵循标准 SQL
- 以**功能丰富、扩展性强、类型严谨**著称
- 数据类型极其丰富：JSONB、数组、UUID、范围类型、网络类型、几何类型
- 支持事务性 DDL、丰富的索引类型（GIN / GiST / BRIN）、强大的窗口函数与 CTE
- 生态：云原生 Postgres 衍生品众多（Greenplum、Citus、TimescaleDB）

### 2.2 与 MySQL 的核心语义差异（重点）

#### 差异 1：类型严格 —— 第一大坑

```sql
-- MySQL：宽松隐式转换，能跑
SELECT * FROM users WHERE id = '1';      -- 字符串自动转数字

-- PostgreSQL：直接报错
SELECT * FROM users WHERE id = '1';
-- ERROR: operator does not exist: integer = character varying
```

修正方式：

```sql
SELECT * FROM users WHERE id = 1;            -- 用正确类型
SELECT * FROM users WHERE id = '1'::int;     -- 显式转换
SELECT * FROM users WHERE id = CAST('1' AS int);
```

#### 差异 2：标识符大小写折叠

```sql
-- PostgreSQL：未加引号的标识符一律折叠为【小写】
CREATE TABLE "User" (id int);    -- 表名真的是 User
SELECT * FROM User;              -- 报错！被折叠成 user，找不到
SELECT * FROM "User";            -- 正确

CREATE TABLE User2 (id int);     -- 折叠为 user2
SELECT * FROM USER2;             -- 能查到（也折叠为 user2）
```

> 结论：**PostgreSQL 里不要用双引号建表**，全部用小写 + 下划线，能省掉大量麻烦。

#### 差异 3：引号语义

```sql
-- PostgreSQL / Oracle
"identifier"   -- 双引号 = 标识符（表名、字段名）
'string'       -- 单引号 = 字符串

-- MySQL
`identifier`   -- 反引号 = 标识符
'string'       -- 单引号 = 字符串（双引号在非 ANSI 模式下也是字符串）
```

#### 差异 4：空字符串 ≠ NULL（PG 与 Oracle 一致，与 MySQL 不同）

```sql
-- PostgreSQL
SELECT '' IS NULL;          -- false，空串就是空串
INSERT INTO t(name) VALUES ('');
SELECT * FROM t WHERE name = '';   -- 能查到
```

#### 差异 5：事务性 DDL（PG 的加分项）

```sql
-- PostgreSQL：DDL 可以回滚
BEGIN;
CREATE TABLE tmp_test (id int);
ROLLBACK;
-- 表真的没被创建

-- MySQL：DDL 会隐式提交，无法回滚
```

### 2.3 语法速查表

| 功能 | PostgreSQL | MySQL |
|------|-----------|-------|
| 自增主键 | `id SERIAL PRIMARY KEY`<br>`id int GENERATED ALWAYS AS IDENTITY` | `id INT AUTO_INCREMENT PRIMARY KEY` |
| 分页 | `LIMIT 10 OFFSET 20` | `LIMIT 20, 10` |
| 取前 N 行 | `FETCH FIRST 10 ROWS ONLY` | `LIMIT 10` |
| UPSERT | `ON CONFLICT (id) DO UPDATE SET ...` | `ON DUPLICATE KEY UPDATE ...` |
| 更新并返回 | `UPDATE ... RETURNING id, name` | 不支持 |
| 插入并返回 | `INSERT ... RETURNING id` | 用 `LAST_INSERT_ID()` |
| 字符串拼接 | `'a' \|\| 'b'` | `CONCAT('a','b')` |
| 当前时间 | `now()` / `CURRENT_TIMESTAMP` | `NOW()` |
| 判空替换 | `COALESCE(a, b)` | `IFNULL(a, b)` |
| 类型转换 | `'1'::int` / `CAST('1' AS int)` | `CAST('1' AS SIGNED)` |
| 布尔类型 | 原生 `boolean`（true/false） | `TINYINT(1)` |
| 正则匹配 | `~ 'pattern'` / `~* 'pattern'` | `REGEXP 'pattern'` |
| 序列 | `CREATE SEQUENCE seq; nextval('seq')` | 无独立序列 |
| 查看表结构 | `\d table_name` | `DESC table_name` |
| 查看所有表 | `\dt` | `SHOW TABLES` |

### 2.4 特色能力（PG 的精华）

#### 数组类型

```sql
CREATE TABLE article (
    id     serial PRIMARY KEY,
    title  text,
    tags   text[]          -- 数组列
);

INSERT INTO article (title, tags) VALUES ('PG 入门', ARRAY['database','sql']);

-- 判断数组是否包含某元素
SELECT * FROM article WHERE 'sql' = ANY(tags);
SELECT * FROM article WHERE tags @> ARRAY['sql'];     -- 包含
SELECT * FROM article WHERE tags && ARRAY['sql'];     -- 有交集
```

#### JSONB（强索引能力，MySQL 的 JSON 远不如它）

```sql
CREATE TABLE event (
    id   serial PRIMARY KEY,
    data jsonb
);

INSERT INTO event (data) VALUES ('{"user":{"name":"alice","age":18}}');

-- 取嵌套字段：-> 返回 jsonb，->> 返回 text
SELECT data -> 'user' ->> 'name' AS name FROM event;

-- 建 GIN 索引，让 JSONB 查询走索引
CREATE INDEX idx_event_data ON event USING GIN (data);

-- 包含查询
SELECT * FROM event WHERE data @> '{"user":{"name":"alice"}}';
```

#### 窗口函数与 CTE

```sql
-- 按部门内工资排名（MySQL 8.0 也支持，PG 支持更早）
SELECT name, dept, salary,
       ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary DESC) AS rn
FROM employee;

-- 递归 CTE：查组织树
WITH RECURSIVE org AS (
    SELECT id, name, parent_id, 1 AS lvl FROM dept WHERE parent_id IS NULL
    UNION ALL
    SELECT d.id, d.name, d.parent_id, o.lvl + 1
    FROM dept d JOIN org o ON d.parent_id = o.id
)
SELECT * FROM org ORDER BY lvl;
```

#### 强大的索引类型

| 索引类型 | 适用场景 |
|---------|---------|
| B-tree | 默认，等值 / 范围 / 排序 |
| GIN | 数组、JSONB、全文检索 |
| GiST | 几何、范围类型、全文检索 |
| BRIN | 超大表、物理有序数据（如时间序列） |
| Hash | 仅等值查询 |

```sql
-- 函数索引 + 部分索引
CREATE INDEX idx_lower_email ON users (LOWER(email));
CREATE INDEX idx_active_user ON users (id) WHERE status = 'ACTIVE';
```

### 2.5 性能与运维要点

| 要点 | 说明 |
|------|------|
| **MVCC 机制** | 旧版本数据留在表内，需要 `VACUUM` 回收；长期不清理会膨胀（bloat） |
| **VACUUM** | `VACUUM ANALYZE table_name;` 回收空间 + 更新统计信息 |
| **连接模型** | 每个连接一个**进程**，开销大，连接数不能多，生产需配 **PgBouncer** 连接池 |
| **执行计划** | `EXPLAIN ANALYZE SELECT ...` 会真实执行并给出实际耗时，比 MySQL EXPLAIN 信息丰富 |
| **锁** | 行级锁 + MVCC，读不阻塞写；但 DDL 会加 AccessExclusiveLock |
| **autovacuum** | 默认开启，但高写入表常需手工调参 |

```sql
-- 查看执行计划（不执行）
EXPLAIN SELECT * FROM users WHERE id = 1;

-- 真实执行并输出耗时
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT * FROM users WHERE id = 1;
```

### 2.6 PostgreSQL 学习检查点

- [ ] 能独立用 Docker 起库并连接
- [ ] 理解为什么 `WHERE id = '1'` 会报错
- [ ] 理解大小写折叠，知道为何不用双引号建表
- [ ] 会用 `SERIAL` / `IDENTITY` 建自增主键
- [ ] 会用 `ON CONFLICT` 实现 UPSERT
- [ ] 会用 `RETURNING` 取回插入/更新后的值
- [ ] 能写出数组查询与 JSONB 查询并建 GIN 索引
- [ ] 会用 `EXPLAIN ANALYZE` 分析慢查询
- [ ] 知道 `VACUUM` 的作用与触发时机

---

## 三、Oracle 篇

### 3.1 定位与特点

- 商业关系型数据库的标杆，金融、电信、政企核心系统的默认选择
- 极强的稳定性、高可用能力（RAC、DataGuard）与完善的运维体系
- PL/SQL 是一套完整的存储过程语言（包、游标、异常处理、触发器）
- **国产数据库的主要对标对象**——达梦 DM8 等主打 Oracle 兼容，正因存量系统庞大

### 3.2 与 MySQL 的核心语义差异（重点）

#### 差异 1：默认不自动提交 —— 最致命

```sql
-- MySQL：autocommit = 1，INSERT 立刻生效
-- Oracle：默认不提交，必须显式 COMMIT，否则其他会话根本读不到
INSERT INTO users (id, name) VALUES (1, 'alice');
COMMIT;     -- 忘了这句，数据就像"丢了"（实际是未提交）
```

> 排查提示：应用连 Oracle 后"插入成功但查不到"，99% 是漏了 `COMMIT`。

#### 差异 2：空字符串 = NULL

```sql
-- Oracle：'' 会被当作 NULL 存储
INSERT INTO users (name) VALUES ('');       -- 实际存进去的是 NULL

SELECT * FROM users WHERE name = '';        -- 永远查不到（等值比较遇 NULL 为 UNKNOWN）
SELECT * FROM users WHERE name IS NULL;     -- 正确
```

> 这是 Oracle 独有行为，MySQL / PG 里 `''` 都是合法的空串。

#### 差异 3：没有 LIMIT，分页靠 ROWNUM 或 OFFSET FETCH

```sql
-- 12c 之前：用 ROWNUM（注意 ROWNUM 在排序前分配，必须先子查询排序）
SELECT * FROM (
    SELECT t.*, ROWNUM rn FROM (
        SELECT * FROM users ORDER BY id
    ) t WHERE ROWNUM <= 20
) WHERE rn > 10;

-- 12c 之后：标准分页语法
SELECT * FROM users ORDER BY id
OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY;
```

#### 差异 4：自增要自己造

```sql
-- Oracle 12c 之前：序列 + 触发器
CREATE SEQUENCE seq_user_id START WITH 1 INCREMENT BY 1;

CREATE OR REPLACE TRIGGER trg_user_id
BEFORE INSERT ON users
FOR EACH ROW
BEGIN
    IF :NEW.id IS NULL THEN
        SELECT seq_user_id.NEXTVAL INTO :NEW.id FROM dual;
    END IF;
END;
/

-- 12c 之后：可用 IDENTITY
CREATE TABLE users (
    id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR2(50)
);
```

> 注意 Oracle 特有的 `dual` 表——查询常量、取序列值都要 `FROM dual`。

### 3.3 语法速查表

| 功能 | Oracle | MySQL |
|------|--------|-------|
| 自增 | `SEQUENCE` + `TRIGGER`（12c+ 可 `IDENTITY`） | `AUTO_INCREMENT` |
| 分页 | `OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY`<br>老版本 `ROWNUM` | `LIMIT 10, 10` |
| 字符串拼接 | `'a' \|\| 'b'` | `CONCAT('a','b')` |
| 字符串类型 | `VARCHAR2(50)` | `VARCHAR(50)` |
| 数字类型 | `NUMBER(10,2)` | `DECIMAL(10,2)` / `INT` |
| 日期时间 | `DATE`（**含时分秒**）、`TIMESTAMP` | `DATE` / `DATETIME` |
| 当前时间 | `SYSDATE` / `SYSTIMESTAMP` | `NOW()` |
| 判空替换 | `NVL(a, b)` / `NVL2` / `COALESCE` | `IFNULL(a, b)` |
| 转字符串 | `TO_CHAR(date_col, 'YYYY-MM-DD')` | `DATE_FORMAT()` |
| 转日期 | `TO_DATE('2024-01-01','YYYY-MM-DD')` | `STR_TO_DATE()` |
| 转数字 | `TO_NUMBER('123')` | `CAST('123' AS SIGNED)` |
| 空串 | `''` 即 `NULL` | `''` 是空串 |
| 事务 | 默认手动 `COMMIT` | 默认自动提交 |
| 修改表结构 | `ALTER TABLE ... ADD (...)` | `ALTER TABLE ... ADD COLUMN` |
| 查看表结构 | `DESC users;` | `DESC users;` |
| 查看所有表 | `SELECT * FROM user_tables;` | `SHOW TABLES;` |
| 注释 | `COMMENT ON COLUMN users.name IS '姓名';` | 建表时 `COMMENT '姓名'` |

### 3.4 PL/SQL 入门

PL/SQL 是 Oracle 的存储过程语言，是 Oracle 学习成本的主要来源。

#### 基本结构

```sql
DECLARE
    v_name   VARCHAR2(50);
    v_count  NUMBER := 0;
BEGIN
    SELECT name INTO v_name FROM users WHERE id = 1;

    IF v_name IS NOT NULL THEN
        DBMS_OUTPUT.PUT_LINE('名字：' || v_name);
    ELSE
        DBMS_OUTPUT.PUT_LINE('未找到');
    END IF;

EXCEPTION
    WHEN NO_DATA_FOUND THEN
        DBMS_OUTPUT.PUT_LINE('没有这条数据');
    WHEN TOO_MANY_ROWS THEN
        DBMS_OUTPUT.PUT_LINE('返回了多行');
    WHEN OTHERS THEN
        DBMS_OUTPUT.PUT_LINE('异常：' || SQLERRM);
END;
/
```

> 结尾的 `/` 是 SQL*Plus 的执行符，别忘了。

#### 存储过程

```sql
CREATE OR REPLACE PROCEDURE add_user(
    p_id   IN  NUMBER,
    p_name IN  VARCHAR2,
    p_ok   OUT NUMBER
) AS
BEGIN
    INSERT INTO users (id, name) VALUES (p_id, p_name);
    COMMIT;
    p_ok := 1;
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        p_ok := 0;
        RAISE;
END add_user;
/

-- 调用
DECLARE
    v_ok NUMBER;
BEGIN
    add_user(100, 'alice', v_ok);
    DBMS_OUTPUT.PUT_LINE('结果：' || v_ok);
END;
/
```

#### 包（Package）—— Oracle 独有组织方式

```sql
-- 包头（声明）
CREATE OR REPLACE PACKAGE pkg_user AS
    PROCEDURE add_user(p_id NUMBER, p_name VARCHAR2);
    FUNCTION get_name(p_id NUMBER) RETURN VARCHAR2;
END pkg_user;
/

-- 包体（实现）
CREATE OR REPLACE PACKAGE BODY pkg_user AS
    PROCEDURE add_user(p_id NUMBER, p_name VARCHAR2) AS
    BEGIN
        INSERT INTO users (id, name) VALUES (p_id, p_name);
        COMMIT;
    END;

    FUNCTION get_name(p_id NUMBER) RETURN VARCHAR2 AS
        v_name VARCHAR2(50);
    BEGIN
        SELECT name INTO v_name FROM users WHERE id = p_id;
        RETURN v_name;
    END;
END pkg_user;
/
```

#### 游标

```sql
DECLARE
    CURSOR c_user IS SELECT id, name FROM users;
    v_id   users.id%TYPE;
    v_name users.name%TYPE;
BEGIN
    OPEN c_user;
    LOOP
        FETCH c_user INTO v_id, v_name;
        EXIT WHEN c_user%NOTFOUND;
        DBMS_OUTPUT.PUT_LINE(v_id || ' - ' || v_name);
    END LOOP;
    CLOSE c_user;
END;
/
```

> `%TYPE` 表示"和某列同类型"，是 Oracle 的惯用写法，改表结构时不用改代码。

### 3.5 体系知识（真正的成本所在）

#### 数据字典视图

Oracle 把元数据暴露成三类视图，前缀不同，权限范围不同：

| 前缀 | 含义 |
|------|------|
| `USER_` | 当前用户拥有的对象 |
| `ALL_` | 当前用户能访问的对象 |
| `DBA_` | 全库所有对象（需 DBA 权限） |
| `V$` | 动态性能视图（内存、会话、SQL 统计） |

```sql
-- 查自己的表
SELECT table_name FROM user_tables;

-- 查某表的所有列
SELECT column_name, data_type, nullable
FROM user_tab_columns WHERE table_name = 'USERS';

-- 查当前会话
SELECT sid, serial#, username, status FROM v$session;

-- 查最耗 CPU 的 SQL
SELECT sql_id, sql_text, cpu_time FROM v$sql ORDER BY cpu_time DESC FETCH FIRST 10 ROWS ONLY;
```

#### 执行计划与调优

```sql
-- 1. 生成执行计划
EXPLAIN PLAN FOR
SELECT * FROM users WHERE id = 1;

-- 2. 格式化查看
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

-- 3. 查看实际执行统计（需要权限）
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'));
```

关注的要点：

| 项 | 说明 |
|----|------|
| `TABLE ACCESS FULL` | 全表扫描，大表要警惕 |
| `INDEX RANGE SCAN` | 索引范围扫描，正常 |
| `INDEX UNIQUE SCAN` | 唯一索引扫描，最优 |
| `NESTED LOOPS` | 嵌套循环，适合小结果集驱动 |
| `HASH JOIN` | 哈希连接，适合大表关联 |
| `SORT ORDER BY` | 排序开销，可考虑索引消除 |
| `Cost` | 优化器估算代价，越小越好 |

#### 高可用体系

| 组件 | 作用 |
|------|------|
| **RAC**（Real Application Clusters） | 多节点共享存储集群，故障切换、负载均衡 |
| **DataGuard** | 备库容灾，支持物理/逻辑备库 |
| **GoldenGate** | 跨库实时数据复制 |
| **ASM** | Oracle 自动存储管理 |
| **AWR / ASH** | 性能诊断报告，自动采集快照 |

> 这也是国产库（尤其是达梦）着力对标的部分——金融核心系统迁移时会重点考察 RAC / DataGuard 的替代方案。

#### 权限体系

```sql
-- 创建用户
CREATE USER app_user IDENTIFIED BY "Passw0rd"
DEFAULT TABLESPACE users
QUOTA UNLIMITED ON users;

-- 授予系统权限
GRANT CREATE SESSION, CREATE TABLE TO app_user;

-- 授予对象权限
GRANT SELECT, INSERT ON scott.users TO app_user;

-- 通过角色批量授权
CREATE ROLE app_role;
GRANT SELECT, INSERT, UPDATE ON scott.users TO app_role;
GRANT app_role TO app_user;
```

> Oracle 是"一个用户一个 Schema"——`scott.users` 中的 `scott` 既是用户名也是 Schema 名。这与 MySQL 的"database"概念不同。

### 3.6 Oracle 学习检查点

- [ ] 记住默认不自动提交，`COMMIT` 不能忘
- [ ] 记住 `''` 就是 `NULL`
- [ ] 会写 `ROWNUM` 与 `OFFSET FETCH` 两种分页
- [ ] 会用序列 + 触发器实现自增
- [ ] 能写出带异常处理的 PL/SQL 匿名块
- [ ] 会创建存储过程、函数、包
- [ ] 认识 `USER_` / `ALL_` / `DBA_` / `V$` 四类视图
- [ ] 会用 `EXPLAIN PLAN` + `DBMS_XPLAN.DISPLAY` 看执行计划
- [ ] 知道 RAC、DataGuard 分别解决什么问题
- [ ] 理解"用户 = Schema"的模型

---

## 四、三库横向对照表

### 4.1 基础能力对照

| 维度 | MySQL | PostgreSQL | Oracle |
|------|-------|-----------|--------|
| 开源 | 是（GPL / 商业双许可） | 是（PostgreSQL License） | 否（商业） |
| 默认事务提交 | 自动 | 自动 | **手动** |
| 空串处理 | 空串 | 空串 | **= NULL** |
| 标识符折叠 | 平台相关 | 小写 | 大写 |
| 标识符引号 | 反引号 `` ` `` | 双引号 `"` | 双引号 `"` |
| 类型校验 | 宽松 | **严格** | 严格 |
| 分页语法 | `LIMIT o,s` | `LIMIT s OFFSET o` | `OFFSET/FETCH`、`ROWNUM` |
| DDL 事务性 | 否 | **是** | 否 |
| 存储过程语言 | SQL/PSM | PL/pgSQL | PL/SQL |
| JSON 支持 | JSON 类型（索引弱） | **JSONB（索引强）** | 12c+ JSON（较弱） |
| 数组类型 | 无 | **有** | 无（用嵌套表/集合） |
| 窗口函数 | 8.0+ | 有（更早） | 有 |
| 连接模型 | 线程 | **进程** | 进程 |
| 高可用 | 主从复制、MGR | 流复制、Patroni | **RAC、DataGuard** |
| 典型场景 | 互联网、中小系统 | 标准化、GIS、分析、云原生 | 金融、电信、政企核心 |

### 4.2 同一需求三种写法

**建表：**

```sql
-- MySQL
CREATE TABLE users (
    id   INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- PostgreSQL
CREATE TABLE users (
    id   SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT now()
);

-- Oracle
CREATE TABLE users (
    id   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR2(50) NOT NULL,
    created_at DATE DEFAULT SYSDATE
);
```

**UPSERT：**

```sql
-- MySQL
INSERT INTO users (id, name) VALUES (1, 'alice')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- PostgreSQL
INSERT INTO users (id, name) VALUES (1, 'alice')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

-- Oracle（MERGE 是标准做法）
MERGE INTO users u
USING (SELECT 1 AS id, 'alice' AS name FROM dual) s
ON (u.id = s.id)
WHEN MATCHED THEN UPDATE SET u.name = s.name
WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name);
```

**分页取第 11–20 行：**

```sql
-- MySQL
SELECT * FROM users ORDER BY id LIMIT 10, 10;

-- PostgreSQL
SELECT * FROM users ORDER BY id LIMIT 10 OFFSET 10;

-- Oracle（12c+）
SELECT * FROM users ORDER BY id OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY;
```

---

## 五、踩坑清单（迁移必看）

### 通用坑

| # | 坑 | 表现 | 解法 |
|---|----|------|------|
| 1 | **类型不匹配** | PG 下 `WHERE id='1'` 报错 | 传正确类型或显式 `::int` |
| 2 | **标识符大小写** | PG 下 `"User"` 与 `User` 不是同一张表 | 全小写建表，不用引号 |
| 3 | **空串 vs NULL** | Oracle 里 `''` 查不到 | 用 `IS NULL`，业务层避免存空串 |
| 4 | **忘 COMMIT** | Oracle 插入后查不到 | 显式 `COMMIT` 或配置连接自动提交 |
| 5 | **分页语句** | Oracle 用 `LIMIT` 报错 | 用 `OFFSET FETCH` 或 `ROWNUM` |
| 6 | **引号写错** | MySQL 的反引号在 PG/Oracle 里非法 | 按库切换引号习惯 |
| 7 | **日期类型** | Oracle `DATE` 含时分秒，MySQL `DATE` 只有日期 | 明确用 `TIMESTAMP` / `DATETIME` |
| 8 | **序列取值** | Oracle 取自增 ID 需 `RETURNING INTO` | `INSERT ... RETURNING id INTO v_id` |
| 9 | **字符串拼接** | MySQL `CONCAT` 遇 NULL 返回 NULL | 用 `\|\|`（Oracle/PG 遇 NULL 也得 NULL，用 `COALESCE` 兜底） |
| 10 | **大小写敏感表名** | MySQL 在 Linux 上表名敏感，Windows 不敏感 | 统一小写，避免环境差异 |

### PostgreSQL 专属坑

| # | 坑 | 说明 |
|---|----|------|
| 1 | 表膨胀 | 更新频繁的表需要 `VACUUM`，否则磁盘暴涨、查询变慢 |
| 2 | 连接数打满 | 每连接一进程，默认 max_connections=100，生产务必上 PgBouncer |
| 3 | 双引号建表 | `CREATE TABLE "User"` 后续必须永远带引号，极易出错 |
| 4 | `SERIAL` 与序列脱节 | 手工插入指定 ID 后，序列不前进，下次自增会主键冲突，需 `setval` 修正 |
| 5 | 子查询别名 | PG 要求子查询必须有别名 |

```sql
-- 序列不同步的修复
SELECT setval('users_id_seq', (SELECT MAX(id) FROM users));
```

### Oracle 专属坑

| # | 坑 | 说明 |
|---|----|------|
| 1 | 默认不提交 | 最常见问题，务必显式 `COMMIT` |
| 2 | `''` = NULL | 存空串会被静默转成 NULL |
| 3 | `ROWNUM` 排序陷阱 | `WHERE ROWNUM <= 10 ORDER BY x` 会先取行再排序，结果错误 |
| 4 | 分号与斜杠 | 匿名块、存储过程结尾要 `/` 才执行 |
| 5 | `VARCHAR2` 与 `VARCHAR` | 优先用 `VARCHAR2`，别用 `VARCHAR` |
| 6 | 一用户一 Schema | 跨 Schema 访问要写 `schema.table` 或建同义词 |
| 7 | 大小写折叠为大写 | `SELECT * FROM "users"` 找不到小写表 |
| 8 | `TO_CHAR` / `TO_DATE` | 日期与字符串互转必须显式转换，不能隐式依赖格式 |

```sql
-- ROWNUM 正确分页写法：必须先排序再套 ROWNUM
SELECT * FROM (
    SELECT t.*, ROWNUM rn FROM (SELECT * FROM users ORDER BY id) t
    WHERE ROWNUM <= 20
) WHERE rn > 10;
```

---

## 六、实操练习

### 练习 1：把 MySQL 建表语句改写成 PG 与 Oracle

```sql
-- 原始 MySQL 语句
CREATE TABLE `order` (
    `id`         INT AUTO_INCREMENT PRIMARY KEY,
    `user_id`    INT NOT NULL,
    `amount`     DECIMAL(10,2) DEFAULT 0,
    `status`     VARCHAR(20) DEFAULT 'NEW',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

<details>
<summary>参考答案</summary>

```sql
-- PostgreSQL
CREATE TABLE "order" (
    id         SERIAL PRIMARY KEY,
    user_id    INT NOT NULL,
    amount     NUMERIC(10,2) DEFAULT 0,
    status     VARCHAR(20) DEFAULT 'NEW',
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_user ON "order" (user_id);
```

```sql
-- Oracle（order 是保留字，表名建议改 orders）
CREATE TABLE orders (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    NUMBER NOT NULL,
    amount     NUMBER(10,2) DEFAULT 0,
    status     VARCHAR2(20) DEFAULT 'NEW',
    created_at DATE DEFAULT SYSDATE
);
CREATE INDEX idx_user ON orders (user_id);
```

</details>

### 练习 2：验证空串语义差异

```sql
-- 在 PostgreSQL 中执行
SELECT '' IS NULL;              -- 期望 false
INSERT INTO t(name) VALUES ('');
SELECT COUNT(*) FROM t WHERE name = '';      -- 期望 1

-- 在 Oracle 中执行同样语句，对比结果
-- 观察：'' 被存成 NULL，等值查询查不到
```

### 练习 3：事务提交差异

```sql
-- 会话 A（Oracle）
INSERT INTO users (id, name) VALUES (999, 'test');
-- 不要 COMMIT

-- 会话 B（另一个连接）
SELECT * FROM users WHERE id = 999;    -- 查不到！
```

然后在会话 A 执行 `COMMIT`，会话 B 再查——这次能查到。亲手体验一次，记忆最深刻。

### 练习 4：执行计划分析

```sql
-- PostgreSQL
EXPLAIN ANALYZE SELECT * FROM users WHERE id = 1;
-- 试着给 id 建索引，对比前后 Seq Scan / Index Scan 的变化

-- Oracle
EXPLAIN PLAN FOR SELECT * FROM users WHERE id = 1;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);
-- 关注 TABLE ACCESS FULL 与 INDEX RANGE SCAN
```

---

## 七、附录：常用命令速查

### PostgreSQL 命令行（psql）

```
\l              列出所有数据库
\c dbname       切换数据库
\dt             列出所有表
\d tablename    查看表结构
\di             列出索引
\du             列出用户/角色
\x              切换纵向显示（宽表友好）
\e              用编辑器编写 SQL
\timing         显示语句执行耗时
\q              退出
```

### Oracle 命令行（sqlplus）

```
-- 连接（容器内）
docker exec -it oracle sqlplus system/oracle@//localhost:1521/XEPDB1

-- 常用
SELECT * FROM user_tables;                -- 我的表
SELECT * FROM user_tab_columns WHERE table_name='USERS';   -- 列信息
DESC users;                               -- 表结构
SET LINESIZE 200;                          -- 设置行宽
SET PAGESIZE 100;                          -- 设置分页
SET SERVEROUTPUT ON;                       -- 打开 DBMS_OUTPUT
SHOW USER;                                 -- 当前用户
```

### JDBC 连接串

```
# MySQL
jdbc:mysql://localhost:3306/demo?useSSL=false&serverTimezone=Asia/Shanghai

# PostgreSQL
jdbc:postgresql://localhost:5432/demo

# Oracle（SID）
jdbc:oracle:thin:@localhost:1521:XE
# Oracle（Service Name，推荐）
jdbc:oracle:thin:@//localhost:1521/XEPDB1
```

### 依赖坐标（Maven）

```xml
<!-- PostgreSQL -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>

<!-- Oracle（ojdbc11 对应 JDK 11+） -->
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
    <version>23.4.0.24.05</version>
</dependency>
```

---

## 学习节奏建议

| 阶段 | 目标 | 验收方式 |
|------|------|---------|
| 第一阶段 | 装库、连通、跑通 CRUD | 能独立用 Docker 起 PG 与 Oracle 并连上 |
| 第二阶段 | 吃透语义差异 | 亲手踩完"五、踩坑清单"里的每个坑 |
| 第三阶段 | 掌握各自特色 | PG：JSONB + 数组 + EXPLAIN ANALYZE<br>Oracle：PL/SQL + 包 + 执行计划 |
| 第四阶段 | 对齐国产库 | 用达梦/金仓/openGauss 重跑一遍同样的 SQL |

> 核心学习原则：**不要背语法表，要动手把 MySQL 的语句改一遍。** 差异只有在报错时才会真正记住。