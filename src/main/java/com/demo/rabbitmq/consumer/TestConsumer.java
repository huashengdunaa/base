package com.demo.rabbitmq.consumer;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.demo.rabbitmq.config.RabbitMqConfig.TEST_QUEUE;

/**
 * @Author: Sl
 * @Description: TODO
 * @DateTime: 2026/11/2
 * @Version 1.0
 **/
@Component
public class TestConsumer {
    @RabbitListener(queues = TEST_QUEUE)
    public void handleTestMessage(String event, Message message, Channel channel){
        System.out.println(event);

        byte[] body = message.getBody();
        System.out.println(body);

        String messageId = message.getMessageProperties().getMessageId();
        System.out.println("messageId:"+ messageId);

        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            System.out.println("消费异常");
            throw new RuntimeException(e);
        }
    }
}
