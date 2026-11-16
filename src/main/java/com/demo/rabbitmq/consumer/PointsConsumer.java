package com.demo.rabbitmq.consumer;

import com.demo.rabbitmq.config.RabbitMqConfig;
import com.demo.rabbitmq.model.UserRegisteredEvent;
import com.demo.rabbitmq.util.IdempotencyStore;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 用户积分初始化消费者。
 *
 * 失败重试链路：
 *
 *   points.queue ──失败 nack(requeue=false)──> retry.exchange ──> points.retry.queue（TTL 等待）
 *        ^                                                              │ 到期后被 DLX 投回
 *        └──────────────────────────────────────────────────────────────┘
 *
 *   重试次数达到上限 ──> user.dlx ──> user.dlq（最终态，等待人工处理）
 *
 * 三条原则：
 *   1. 失败统一 basicNack(requeue=false)，由队列的 DLX 把消息转进重试队列；
 *      requeue=true 会在本队列原地打转，形成忙循环。
 *   2. 重试次数以 Broker 的 x-death 为准，不用应用内存计数（消息转队列、应用重启都会丢）。
 *   3. 超过上限不再回重试链路，主动投递到最终态队列后 ACK，避免无限重试。
 */
@Component
public class PointsConsumer {

    /** 最大重试次数：x-death 中记录的失败次数达到该值后转入最终态队列 */
    private static final int MAX_RETRY_TIMES = 3;

    /** 模拟业务失败的次数：前 2 次抛异常，第 3 次成功；调到 >= MAX_RETRY_TIMES 可演示转入最终态队列 */
    private static final int MAX_FAIL_TIMES = 2;

    private final IdempotencyStore idempotencyStore;
    private final RabbitTemplate rabbitTemplate;

    public PointsConsumer(IdempotencyStore idempotencyStore, RabbitTemplate rabbitTemplate) {
        this.idempotencyStore = idempotencyStore;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_POINTS)
    public void handlePoints(UserRegisteredEvent event, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String msgId = message.getMessageProperties().getMessageId();

        // 1. 幂等：已成功处理过的消息直接确认，避免重投导致积分重复发放
        //    msgId 为空（如从管理台直接发布的消息）时跳过幂等判断，避免 ConcurrentHashMap 不接受 null key
        if (msgId != null && idempotencyStore.isProcessed(msgId)) {
            System.out.println("[Points-Consumer] 消息已处理过(幂等去重), 直接 ack, msgId=" + msgId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        // 2. 从 x-death 读取已失败次数（每被拒绝一次，Broker 会为 points.queue 累加一条记录）
        int retryTimes = countRetryTimes(message);

        // 3. 重试次数耗尽：转最终态队列，再确认原消息，防止它再次回到重试链路
        if (retryTimes >= MAX_RETRY_TIMES) {
            System.out.println("[Points-Consumer] 重试 " + retryTimes + " 次仍失败, 转入最终态队列, msgId=" + msgId);
            moveToDeadLetter(event, msgId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // 4. 业务处理（前 MAX_FAIL_TIMES 次模拟失败，用于观察重试链路）
            processPoints(event, msgId, retryTimes);

            if (msgId != null) {
                idempotencyStore.markProcessed(msgId);
            }
            channel.basicAck(deliveryTag, false);
            System.out.println("[Points-Consumer] 已 ACK, msgId=" + msgId + ", deliveryTag=" + deliveryTag);

        } catch (Exception e) {
            // 5. 失败：requeue=false，交给 points.queue 的 DLX 转入重试队列，TTL 到期后自动回到本队列
            System.err.println("[Points-Consumer] 第 " + (retryTimes + 1) + " 次消费失败, 转重试队列, msgId=" + msgId
                    + ", error=" + e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 业务处理：初始化用户积分（模拟耗时，前 MAX_FAIL_TIMES 次模拟失败）。
     */
    private void processPoints(UserRegisteredEvent event, String msgId, int retryTimes) throws InterruptedException {
        if (retryTimes < MAX_FAIL_TIMES) {
            throw new IllegalStateException("模拟业务异常");
        }

        System.out.println("[Points-Consumer] 开始初始化用户积分, msgId=" + msgId
                + ", userId=" + event.getUserId() + ", username=" + event.getUsername());

        Thread.sleep(200);

        System.out.println("[Points-Consumer] 用户积分初始化成功, msgId=" + msgId + ", 赠送 100 积分");
    }

    /**
     * 投递到最终态队列（user.dlx -> user.dlq）。
     * 必须保留 messageId，否则死信无法与原始消息关联，幂等也失效。
     */
    private void moveToDeadLetter(UserRegisteredEvent event, String msgId) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE_USER_DLX,
                RabbitMqConfig.ROUTING_KEY_USER_DEAD,
                event,
                message -> {
                    message.getMessageProperties().setMessageId(msgId);
                    return message;
                });
    }

    /**
     * 从消息头 x-death 统计「在 points.queue 被拒绝」的累计次数。
     *
     * x-death 是数组，每经过一次死信都会新增/累加一条记录：
     *   - points.queue 上被 nack          -> queue=points.queue,        reason=rejected
     *   - points.retry.queue 上 TTL 到期  -> queue=points.retry.queue,  reason=expired
     * 只统计 reason=rejected，才是真正的业务失败次数。
     */
    @SuppressWarnings("unchecked")
    private int countRetryTimes(Message message) {
        Object header = message.getMessageProperties().getHeaders().get("x-death");
        if (!(header instanceof List)) {
            return 0;
        }

        int total = 0;
        for (Object item : (List<Object>) header) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> death = (Map<String, Object>) item;
            if (!RabbitMqConfig.QUEUE_POINTS.equals(String.valueOf(death.get("queue")))
                    || !"rejected".equals(String.valueOf(death.get("reason")))) {
                continue;
            }
            Object count = death.get("count");
            total += count instanceof Number ? ((Number) count).intValue() : 1;
        }
        return total;
    }
}
