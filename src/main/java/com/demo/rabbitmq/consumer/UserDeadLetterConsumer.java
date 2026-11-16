package com.demo.rabbitmq.consumer;

import com.demo.rabbitmq.config.RabbitMqConfig;
import com.demo.rabbitmq.entity.UserDeadLetter;
import com.demo.rabbitmq.model.UserRegisteredEvent;
import com.demo.rabbitmq.repository.UserDeadLetterRepository;
import com.demo.rabbitmq.service.UserDeadLetterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;


/**
 * @Author: Sl
 * @Description: 死信消息落库记录
 * @DateTime: 2026/11/16
 * @Version 1.0
 **/
@Component
public class UserDeadLetterConsumer {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserDeadLetterService userDeadLetterService;

    @RabbitListener(queues = RabbitMqConfig.QUEUE_USER_DLQ)
    public void handleDeadLetter(UserRegisteredEvent event, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String dataJson = objectMapper.writeValueAsString(event);
        String messageId = message.getMessageProperties().getMessageId();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String receivedExchange = message.getMessageProperties().getReceivedExchange();
        String exceptionMsg = objectMapper.writeValueAsString(message.getMessageProperties().getXDeathHeader());
        UserDeadLetter userDeadLetter = dataFilled(messageId, dataJson, routingKey, receivedExchange, exceptionMsg);
        userDeadLetterService.save(userDeadLetter);
        channel.basicAck(deliveryTag, false);
    }

    private UserDeadLetter dataFilled(String messageId, String json, String routingKey, String exchange, String exceptionMsg){
        UserDeadLetter userDeadLetter = new UserDeadLetter();
        userDeadLetter.setMsgId(messageId);
        userDeadLetter.setRawData(json);
        userDeadLetter.setTopic(exchange +"."+ routingKey);
        userDeadLetter.setExceptionMsg(exceptionMsg);
        return userDeadLetter;
    }
}
