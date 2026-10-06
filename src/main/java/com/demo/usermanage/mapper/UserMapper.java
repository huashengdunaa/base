package com.demo.usermanage.mapper;

import com.demo.usermanage.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户 Mapper：标准 MyBatis 注解 CRUD（对应 MySQL 表 t_user）。
 *
 * 当前工程用 {@link InMemoryUserMapper} 内存伪数据运行，本接口不贴 @Mapper、
 * 不被 MapperScan 扫描；接入真实 MySQL 时：
 *   1. 启动类开启 @MapperScan 并移除 DataSource/Mybatis 自动配置排除；
 *   2. 删除 InMemoryUserMapper；
 *   本接口的 SQL 无需任何改动。
 *
 * 建表 SQL 参考：
 * CREATE TABLE t_user (
 *   id         BIGINT       NOT NULL AUTO_INCREMENT,
 *   username   VARCHAR(50)  NOT NULL,
 *   password   VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
 *   email      VARCHAR(100) NOT NULL,
 *   status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用 -1已删除',
 *   created_at DATETIME     NOT NULL,
 *   PRIMARY KEY (id),
 *   UNIQUE KEY uk_username (username)
 * );
 */
public interface UserMapper {

    /** 新增用户，自增主键回填到 user.id */
    @Insert("INSERT INTO t_user(username, password, email, status, created_at) " +
            "VALUES(#{username}, #{password}, #{email}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /** 按主键查询（过滤已逻辑删除数据），列名起别名完成下划线到驼峰映射 */
    @Select("SELECT id, username, password, email, status, created_at AS createdAt " +
            "FROM t_user WHERE id = #{id} AND status &lt;&gt; -1")
    User selectById(@Param("id") Long id);

    /** 按用户名查询，用于注册/改名时的唯一性校验 */
    @Select("SELECT id, username, password, email, status, created_at AS createdAt " +
            "FROM t_user WHERE username = #{username} AND status &lt;&gt; -1")
    User selectByUsername(@Param("username") String username);

    /** 分页查询未删除用户，按 id 正序；offset = (page-1) * size */
    @Select("SELECT id, username, password, email, status, created_at AS createdAt " +
            "FROM t_user WHERE status &lt;&gt; -1 ORDER BY id " +
            "LIMIT #{offset}, #{size}")
    List<User> selectPage(@Param("offset") long offset, @Param("size") long size);

    /** 统计未删除用户总数（分页总条数） */
    @Select("SELECT COUNT(*) FROM t_user WHERE status &lt;&gt; -1")
    long countActive();

    /**
     * 动态更新：只更新非 null 字段（&lt;set&gt; 自动处理多余逗号）。
     * 注意 update 方法里没有动态 created_at，创建时间不可改。
     */
    @Update("<script>" +
            "UPDATE t_user " +
            "<set>" +
            "  <if test='username != null'>username = #{username},</if>" +
            "  <if test='password != null'>password = #{password},</if>" +
            "  <if test='email != null'>email = #{email},</if>" +
            "  <if test='status != null'>status = #{status},</if>" +
            "</set>" +
            "WHERE id = #{id} AND status &lt;&gt; -1" +
            "</script>")
    int updateDynamic(User user);

    /** 逻辑删除：把 status 置为 -1，数据行仍保留在表中 */
    @Update("UPDATE t_user SET status = -1 WHERE id = #{id} AND status &lt;&gt; -1")
    int logicDelete(@Param("id") Long id);
}
