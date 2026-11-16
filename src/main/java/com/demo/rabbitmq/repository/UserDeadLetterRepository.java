package com.demo.rabbitmq.repository;

import com.demo.rabbitmq.entity.UserDeadLetter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 死信消息仓储。
 *
 * 基础增删改查由 {@link JpaRepository} 提供：
 *   save / saveAll / findById / findAll / deleteById / deleteAll / count
 * 这里只补充两个排查场景常用的查询。
 */
@Repository
public interface UserDeadLetterRepository extends JpaRepository<UserDeadLetter, Long> {

    /** 按消息 ID 查：同一条消息重投多次时可能落多条，故返回列表 */
    List<UserDeadLetter> findByMsgId(String msgId);

    /** 按落库时间范围分页查询，用于管理端按天排查 */
    Page<UserDeadLetter> findByCreateDateBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);
}
