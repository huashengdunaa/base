package com.demo.rabbitmq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：交换机 / 队列 / 绑定、消息转换器、消息确认机制。
 *
 * 拓扑（Topic 交换机）：
 *
 *   user.exchange (topic)
 *        |
 *        |  routingKey = user.registered
 *        |
 *   +----+----+
 *   |         |
 *   v         v
 * email.queue  points.queue
 *
 * 说明：
 *   - 两个队列绑定到同一个 Topic 交换机、同一个路由键 user.registered。
 *   - 生产者发送一条消息，两个队列都会收到一份副本（发布/订阅）。
 *   - 邮件消费者和积分消费者各自独立消费，互不影响，实现异步解耦。
 */
@Configuration
public class RabbitMqConfig {

    /** Topic 交换机名称 */
    public static final String USER_EXCHANGE = "user.exchange";

    /** 邮件队列 */
    public static final String EMAIL_QUEUE = "email.queue";

    /** 积分队列 */
    public static final String POINTS_QUEUE = "points.queue";

    /** 路由键：用户注册 */
    public static final String ROUTING_KEY_USER_REGISTERED = "user.registered";

    // ========== 交换机 / 队列 / 绑定 ==========

    /** Topic 交换机 */
    @Bean
    public TopicExchange userExchange() {
        // durable=true：交换机持久化，Broker 重启后不丢失
        return new TopicExchange(USER_EXCHANGE, true, false);
    }

    /** 邮件队列（持久化） */
    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE).build();
    }

    /** 积分队列（持久化） */
    @Bean
    public Queue pointsQueue() {
        return QueueBuilder.durable(POINTS_QUEUE).build();
    }

    /** 邮件队列绑定到 user.exchange，路由键 user.registered */
    @Bean
    public Binding emailBinding(Queue emailQueue, TopicExchange userExchange) {
        return BindingBuilder.bind(emailQueue)
                .to(userExchange)
                .with(ROUTING_KEY_USER_REGISTERED);
    }

    /** 积分队列绑定到 user.exchange，路由键 user.registered */
    @Bean
    public Binding pointsBinding(Queue pointsQueue, TopicExchange userExchange) {
        return BindingBuilder.bind(pointsQueue)
                .to(userExchange)
                .with(ROUTING_KEY_USER_REGISTERED);
    }

    // ========== 消息转换器（JSON） ==========

    /**
     * 使用 Jackson 将对象序列化为 JSON 消息体。
     * 生产者与消费者都使用同一个转换器，保证反序列化一致。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ========== RabbitTemplate（生产者 + 发布确认） ==========

    /**
     * 配置 RabbitTemplate：
     *   - 注入 JSON 消息转换器
     *   - 开启 mandatory：消息不可路由时触发 ReturnCallback（而不是静默丢弃）
     *   - 设置 ConfirmCallback：Broker 收到消息后回调确认（publisher confirm）
     *   - 设置 ReturnsCallback：消息无法路由到任何队列时回调
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);

        // mandatory=true：开启不可路由消息的回调（配合 publisher-returns=true）
        rabbitTemplate.setMandatory(true);

        // ===== 发布确认（Publisher Confirm）=====
        // Broker 成功收到消息（写入磁盘/镜像）后回调 ack=true；
        // 若 Broker 内部异常导致消息丢失则 ack=false。
        // 注意：ConfirmCallback 只保证消息到达 Broker，不保证被消费者成功消费。
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData != null ? correlationData.getId() : "null";
            if (ack) {
                System.out.println("[Publisher-Confirm] 消息已被 Broker 确认接收, msgId=" + id);
            } else {
                System.err.println("[Publisher-Confirm] 消息未被 Broker 接收, msgId=" + id
                        + ", cause=" + cause);
                // 生产环境：此处应做补偿，例如重试发送或落库待重试
            }
        });

        // ===== 消息不可路由回调（ReturnsCallback）=====
        // 当交换机找不到匹配的队列时触发（例如路由键写错）。
        rabbitTemplate.setReturnsCallback(returned -> {
            System.err.println("[Publisher-Return] 消息不可路由被退回: "
                    + "replyCode=" + returned.getReplyCode()
                    + ", replyText=" + returned.getReplyText()
                    + ", exchange=" + returned.getExchange()
                    + ", routingKey=" + returned.getRoutingKey());
            // 生产环境：此处应记录并补偿
        });

        return rabbitTemplate;
    }
}
