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
 * RabbitMQ 配置：交换机 / 队列 / 绑定、消息转换器、发布确认。
 *
 * 命名规范（全模块统一）：
 *   常量前缀   EXCHANGE_ / QUEUE_ / ROUTING_KEY_，一眼看出是哪类资源
 *   资源值     <模块>.<用途>，队列以 .queue / .dlq 结尾
 *
 * 拓扑：
 *
 *   user.exchange ──user.registered──┬──> email.queue
 *                                    └──> points.queue
 *                                              │ 消费失败 nack(requeue=false)
 *                                              v
 *                                        retry.exchange ──points.retry──> points.retry.queue (TTL 5s)
 *                                              ^                                 │ 到期
 *                                              └──────points.back────────────────┘
 *                                                       回到 points.queue
 *
 *   重试次数达上限（应用判定）──> user.dlx ──user.dead──> user.dlq（最终态，常驻不删）
 *
 * 关键点：
 *   - retry.exchange 同时承担「去重试队列」和「回业务队列」两个方向，
 *     靠 routingKey 区分：points.retry 进重试队列，points.back 回业务队列。
 *   - user.dlq 不设 TTL：死信是排查依据，必须长期保留。
 *   - user.dlq 只绑定 user.dlx，不绑业务交换机，避免业务消息误入死信。
 */
@Configuration
public class RabbitMqConfig {

    // ==================== 交换机 ====================

    /** 业务事件交换机（Topic） */
    public static final String EXCHANGE_USER = "user.exchange";

    /** 重试交换机（Topic）：负责「进重试队列」与「回业务队列」两个方向 */
    public static final String EXCHANGE_RETRY = "retry.exchange";

    /** 最终死信交换机（Topic） */
    public static final String EXCHANGE_USER_DLX = "user.dlx";

    // ==================== 队列 ====================

    /** 欢迎邮件队列 */
    public static final String QUEUE_EMAIL = "email.queue";

    /** 积分初始化队列 */
    public static final String QUEUE_POINTS = "points.queue";

    /** 积分重试队列：只等待 TTL 到期，不挂消费者 */
    public static final String QUEUE_POINTS_RETRY = "points.retry.queue";

    /** 用户死信队列：最终态，落库后人工处理 */
    public static final String QUEUE_USER_DLQ = "user.dlq";

    // ==================== 路由键 ====================

    /** 用户注册事件 */
    public static final String ROUTING_KEY_USER_REGISTERED = "user.registered";

    /** 业务队列 -> 重试队列 */
    public static final String ROUTING_KEY_POINTS_RETRY = "points.retry";

    /** 重试队列 -> 业务队列（TTL 到期后回流） */
    public static final String ROUTING_KEY_POINTS_BACK = "points.back";

    /** 最终死信投递 */
    public static final String ROUTING_KEY_USER_DEAD = "user.dead";

    // ==================== 参数 ====================

    /** 重试等待时间：生产建议按退避策略递增，这里固定 5 秒便于观察 */
    public static final long POINTS_RETRY_TTL_MS = 5_000L;

    // ==================== 交换机 ====================

    @Bean
    public TopicExchange userExchange() {
        // durable=true：交换机持久化，Broker 重启后不丢失
        return new TopicExchange(EXCHANGE_USER, true, false);
    }

    @Bean
    public TopicExchange retryExchange() {
        return new TopicExchange(EXCHANGE_RETRY, true, false);
    }

    @Bean
    public TopicExchange userDeadLetterExchange() {
        return new TopicExchange(EXCHANGE_USER_DLX, true, false);
    }

    // ==================== 队列 ====================

    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(QUEUE_EMAIL).build();
    }

    /** 积分队列：消费失败（nack 且不 requeue）后经 retry.exchange 进入重试队列 */
    @Bean
    public Queue pointsQueue() {
        return QueueBuilder.durable(QUEUE_POINTS)
                .deadLetterExchange(EXCHANGE_RETRY)
                .deadLetterRoutingKey(ROUTING_KEY_POINTS_RETRY)
                .build();
    }

    /**
     * 积分重试队列：无消费者，消息在此静默等待 TTL 到期，
     * 到期后由队列的 DLX 按 points.back 送回 points.queue。
     */
    @Bean
    public Queue pointsRetryQueue() {
        return QueueBuilder.durable(QUEUE_POINTS_RETRY)
                .ttl((int) POINTS_RETRY_TTL_MS)
                .deadLetterExchange(EXCHANGE_RETRY)
                .deadLetterRoutingKey(ROUTING_KEY_POINTS_BACK)
                .build();
    }

    /** 最终死信队列：不设 TTL，保证死信可长期排查与重放 */
    @Bean
    public Queue userDeadLetterQueue() {
        return QueueBuilder.durable(QUEUE_USER_DLQ).build();
    }

    // ==================== 绑定 ====================

    @Bean
    public Binding emailBinding(Queue emailQueue, TopicExchange userExchange) {
        return BindingBuilder.bind(emailQueue).to(userExchange).with(ROUTING_KEY_USER_REGISTERED);
    }

    @Bean
    public Binding pointsBinding(Queue pointsQueue, TopicExchange userExchange) {
        return BindingBuilder.bind(pointsQueue).to(userExchange).with(ROUTING_KEY_USER_REGISTERED);
    }

    /** 重试队列：接收 retry.exchange 上 points.retry 的消息 */
    @Bean
    public Binding pointsRetryBinding(Queue pointsRetryQueue, TopicExchange retryExchange) {
        return BindingBuilder.bind(pointsRetryQueue).to(retryExchange).with(ROUTING_KEY_POINTS_RETRY);
    }

    /** 回流绑定：TTL 到期的消息经 points.back 回到业务队列，形成重试闭环 */
    @Bean
    public Binding pointsBackBinding(Queue pointsQueue, TopicExchange retryExchange) {
        return BindingBuilder.bind(pointsQueue).to(retryExchange).with(ROUTING_KEY_POINTS_BACK);
    }

    /** 最终死信队列绑定到 user.dlx */
    @Bean
    public Binding userDeadLetterBinding(Queue userDeadLetterQueue, TopicExchange userDeadLetterExchange) {
        return BindingBuilder.bind(userDeadLetterQueue).to(userDeadLetterExchange).with(ROUTING_KEY_USER_DEAD);
    }

    // ==================== 消息转换器（JSON） ====================

    /**
     * 使用 Jackson 将对象序列化为 JSON 消息体。
     * 生产者与消费者都使用同一个转换器，保证反序列化一致。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ==================== RabbitTemplate（生产者 + 发布确认） ====================

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
