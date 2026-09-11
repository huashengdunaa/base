package com.demo.rabbitmq.producer;

import com.demo.rabbitmq.config.RabbitMqConfig;
import com.demo.rabbitmq.model.UserRegisteredEvent;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 用户事件生产者。
 *
 * 职责：用户注册成功后，向 user.exchange 发送一条路由键为 user.registered 的消息。
 *
 * 发布确认机制（Publisher Confirm）：
 *   - 发送时传入 CorrelationData（携带 msgId），Broker 确认后回调 ConfirmCallback。
 *   - ConfirmCallback 在 RabbitMqConfig#rabbitTemplate 中统一配置。
 *
 * 消息持久化：
 *   - 交换机/队列均为 durable，消息默认 deliveryMode=PERSISTENT，Broker 重启不丢失。
 */
@Component
public class UserEventProducer {

    private final RabbitTemplate rabbitTemplate;

    public UserEventProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送"用户已注册"事件。
     *
     * @param event 用户注册事件
     * @return 本次消息的唯一 ID（msgId），用于幂等与追踪
     */
    public String sendUserRegistered(UserRegisteredEvent event) {
        // 全局唯一消息 ID，写入 MessageProperties.messageId，供消费者幂等去重
        String msgId = UUID.randomUUID().toString().replace("-", "");

        // CorrelationData 用于将 ConfirmCallback 与具体消息关联
        CorrelationData correlationData = new CorrelationData(msgId);

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.USER_EXCHANGE,
                RabbitMqConfig.ROUTING_KEY_USER_REGISTERED,
                event,
                message -> {
                    // 将 msgId 写入消息头，消费者可从 messageProperties.messageId 取出
                    message.getMessageProperties().setMessageId(msgId);
                    return message;
                },
                correlationData
        );

        System.out.println("[Producer] 已发送用户注册事件, msgId=" + msgId + ", event=" + event);
        return msgId;
    }
}
