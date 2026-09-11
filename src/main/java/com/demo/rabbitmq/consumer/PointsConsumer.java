package com.demo.rabbitmq.consumer;

import com.demo.rabbitmq.config.RabbitMqConfig;
import com.demo.rabbitmq.model.UserRegisteredEvent;
import com.demo.rabbitmq.util.IdempotencyStore;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 用户积分初始化消费者。
 *
 * 消费 points.queue 的消息，模拟初始化用户积分。
 *
 * 本消费者用于【验证消息重试】：
 *   - 前 MAX_RETRY 次消费会主动抛出异常，basicNack(requeue=true) 让消息重新入队；
 *   - 达到重试次数后正常处理并 ack。
 *   通过日志可以观察到同一条 msgId 被多次消费，最终成功。
 *
 * 关键点：
 *   1. 手动 ACK / NACK 控制消息生命周期。
 *   2. 幂等性：即使重试，也只在最后一次成功时标记 processed，避免重复初始化积分。
 *      （实际业务可结合"用户积分是否已初始化"的状态判断做双层幂等兜底。）
 *   3. 生产环境建议：达到最大重试次数后应投递到死信队列（DLQ），而不是无限 requeue，
 *      避免消息在队列中无限循环。
 */
@Component
public class PointsConsumer {

    /** 模拟失败的次数：前 2 次失败，第 3 次成功 */
    private static final int MAX_FAIL_TIMES = 2;

    private final IdempotencyStore idempotencyStore;

    public PointsConsumer(IdempotencyStore idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    @RabbitListener(queues = RabbitMqConfig.POINTS_QUEUE)
    public void handlePoints(UserRegisteredEvent event, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String msgId = message.getMessageProperties().getMessageId();

        // ===== 幂等性校验 =====
        if (idempotencyStore.isProcessed(msgId)) {
            System.out.println("[Points-Consumer] 消息已处理过(幂等去重), 直接 ack, msgId=" + msgId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        int retry = idempotencyStore.getRetryCount(msgId);

        // ===== 模拟异常：前 MAX_FAIL_TIMES 次消费失败 =====
        if (retry < MAX_FAIL_TIMES) {
            int next = idempotencyStore.incrementRetry(msgId);
            System.err.println("[Points-Consumer] 第 " + next + " 次消费失败(模拟异常), msgId=" + msgId
                    + ", 重新入队等待重试");
            // 模拟异常：basicNack + requeue=true，消息回到队首，很快会被再次投递
            channel.basicNack(deliveryTag, false, true);
            return;
        }

        try {
            // ===== 业务处理：初始化用户积分（模拟）=====
            System.out.println("[Points-Consumer] 开始初始化用户积分, msgId=" + msgId
                    + ", userId=" + event.getUserId() + ", username=" + event.getUsername());

            Thread.sleep(200);

            System.out.println("[Points-Consumer] 用户积分初始化成功, msgId=" + msgId
                    + ", 赠送 100 积分");

            // ===== 标记已处理 + ACK =====
            idempotencyStore.markProcessed(msgId);
            channel.basicAck(deliveryTag, false);
            System.out.println("[Points-Consumer] 已 ACK, deliveryTag=" + deliveryTag);

        } catch (Exception e) {
            System.err.println("[Points-Consumer] 处理失败, nack 重投, msgId=" + msgId
                    + ", error=" + e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
