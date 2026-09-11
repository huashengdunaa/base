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
 * 欢迎邮件消费者。
 *
 * 消费 email.queue 的消息，模拟发送欢迎邮件。
 *
 * 关键点：
 *   1. 手动 ACK（acknowledge-mode=manual）：业务处理成功后 basicAck，失败 basicNack。
 *   2. 幂等性：用 msgId 去重，同一条消息只处理一次。
 *   3. 本消费者不模拟异常，走正常 ack 流程，与 PointsConsumer 的重试流程形成对比。
 */
@Component
public class EmailConsumer {

    private final IdempotencyStore idempotencyStore;

    public EmailConsumer(IdempotencyStore idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    @RabbitListener(queues = RabbitMqConfig.EMAIL_QUEUE)
    public void handleEmail(UserRegisteredEvent event, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String msgId = message.getMessageProperties().getMessageId();

        try {
            // ===== 幂等性校验 =====
            // 若该 msgId 已成功处理过，直接 ack 丢弃，避免重复发送邮件
            if (idempotencyStore.isProcessed(msgId)) {
                System.out.println("[Email-Consumer] 消息已处理过(幂等去重), 直接 ack, msgId=" + msgId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 业务处理：发送欢迎邮件（模拟）=====
            System.out.println("[Email-Consumer] 开始发送欢迎邮件, msgId=" + msgId
                    + ", email=" + event.getEmail());

            // 模拟邮件发送耗时
            Thread.sleep(200);

            System.out.println("[Email-Consumer] 欢迎邮件发送成功, msgId=" + msgId
                    + ", to=" + event.getEmail());

            // ===== 标记已处理 + ACK =====
            idempotencyStore.markProcessed(msgId);
            channel.basicAck(deliveryTag, false);
            System.out.println("[Email-Consumer] 已 ACK, deliveryTag=" + deliveryTag);

        } catch (Exception e) {
            // 业务异常：basicNack 并 requeue=true，消息重新入队等待重试
            System.err.println("[Email-Consumer] 处理失败, 准备 nack 重投, msgId=" + msgId
                    + ", error=" + e.getMessage());
            // multiple=false：仅拒绝当前消息；requeue=true：重新入队
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
