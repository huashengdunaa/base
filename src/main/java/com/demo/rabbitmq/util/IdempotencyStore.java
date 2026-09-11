package com.demo.rabbitmq.util;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 幂等性存储 + 重试计数器（内存版）。
 *
 * 生产环境应替换为 Redis（SETNX + 过期时间），原因：
 *   1. 多实例部署时内存态不共享，无法跨节点去重；
 *   2. 进程重启后内存态丢失，可能导致重启期间的消息被重复消费。
 *
 * 这里用 ConcurrentHashMap 模拟 Redis 的 SETNX 语义，保证 Demo 单节点下可演示。
 *
 * 设计：
 *   - processed 集合：标记某 msgId 是否已经成功消费过（用于快速去重）。
 *   - retryCount 映射：记录某 msgId 当前已重试的次数（用于模拟重试 N 次后成功）。
 */
@Component
public class IdempotencyStore {

    /** msgId -> 是否已处理完成（成功 ack） */
    private final ConcurrentHashMap<String, Boolean> processed = new ConcurrentHashMap<>();

    /** msgId -> 已重试次数 */
    private final ConcurrentHashMap<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

    /**
     * 判断消息是否已被成功处理。
     * 生产环境等价于：redis.get(msgId) != null
     */
    public boolean isProcessed(String msgId) {
        return Boolean.TRUE.equals(processed.get(msgId));
    }

    /**
     * 标记消息已成功处理。
     * 生产环境等价于：redis.set(msgId, "1", 24h)
     */
    public void markProcessed(String msgId) {
        processed.put(msgId, Boolean.TRUE);
    }

    /**
     * 获取当前重试次数。
     */
    public int getRetryCount(String msgId) {
        return retryCount.computeIfAbsent(msgId, k -> new AtomicInteger(0)).get();
    }

    /**
     * 重试次数 +1，返回自增后的值。
     */
    public int incrementRetry(String msgId) {
        return retryCount.computeIfAbsent(msgId, k -> new AtomicInteger(0)).incrementAndGet();
    }
}
