# RabbitMQ 学习文档（Java 初级开发向）

面向 Java 初级开发，以项目中的 `com.demo.rabbitmq` 模块为样本，讲清 RabbitMQ 的核心概念、消息流转、可靠性保障和常见问题。
命令速查见同目录 [rabbitmq-commands.md](rabbitmq-commands.md)。

学习完成后应能：

- 说清 交换机 / 队列 / 绑定 / 路由键 的关系；
- 独立设计一个「用户注册 → 异步发邮件 + 初始化积分」的 MQ 拓扑；
- 理解并实现 发布确认、持久化、手动 ACK、幂等消费；
- 掌握 TTL 与死信队列（DLX / DLQ），能搭建「失败 → 延迟重试 → 最终死信」链路；
- 遇到「消息没消费」「消息重复」「消息丢失」「消息过期」时知道从哪查。

运行环境：RabbitMQ 3.13（Docker 容器 `rabbitmq-learning`），Spring Boot 2.7.18 + Spring AMQP，Java 11。

---

## 一、先建立整体模型

RabbitMQ 是**消息代理（Broker）**：生产者把消息发给 Broker，Broker 按规则把消息存进队列，消费者再从队列取走处理。生产者与消费者互不感知，从而实现**异步**与**解耦**。

| 概念 | 一句话解释 | 项目中的对应物 |
|---|---|---|
| Producer 生产者 | 发消息的一方 | `UserEventProducer` |
| Consumer 消费者 | 收消息的一方 | `EmailConsumer`、`PointsConsumer` |
| Broker 服务端 | 安装 RabbitMQ 的进程，负责收存转发 | Docker 容器 `rabbitmq-learning` |
| Connection 连接 | 应用与 Broker 之间的一条 TCP 连接 | Spring AMQP 自动维护 |
| Channel 信道 | 连接内的逻辑通道，发/收消息都走它 | 每次消费对应一个 Channel |
| Exchange 交换机 | 接收生产者消息，按规则路由到队列 | `user.exchange`（topic） |
| Queue 队列 | 真正存消息的地方，先进先出 | `email.queue`、`points.queue` |
| Binding 绑定 | 交换机与队列之间的路由规则 | `emailBinding`、`pointsBinding` |
| RoutingKey 路由键 | 生产者贴在消息上的「地址标签」 | `user.registered` |
| vhost 虚拟主机 | 逻辑隔离单元，权限按 vhost 分配 | 默认 `/` |

两个容易混淆的点：

1. **消息不会直接发给队列**，而是发给交换机；交换机再根据绑定关系投递到队列。
2. **Connection 很重、Channel 很轻**。一个应用共用少数几条连接，每条连接上开多个信道。RabbitMQ 的并发限制、prefetch 等都以 Channel 为单位。

---

## 二、消息的完整流转路径

项目拓扑（Topic 交换机，两个队列绑定同一个路由键）：

```
UserService.register()
        |
        | 写库(模拟) + 发事件
        v
UserEventProducer
        |  exchange = user.exchange
        |  routingKey = user.registered
        v
  [user.exchange]  (topic)
        |                    \
        | 绑定匹配            \ 绑定匹配
        v                      v
  [email.queue]          [points.queue]
        |                      |
        v                      v
  EmailConsumer          PointsConsumer
 (发欢迎邮件)            (初始化积分，演示重试)
```

一次发送的完整链路：

1. 生产者通过 Channel 把消息发到 `user.exchange`，携带路由键 `user.registered`。
2. Broker 查找该交换机上所有绑定，发现两个队列的绑定键都精确匹配 `user.registered`。
3. Broker 把消息**复制两份**，分别写入 `email.queue` 和 `points.queue`（各自维护一份）。
4. 消费者订阅着自己的队列，Broker 按 prefetch 推送给消费者。
5. 消费者处理成功后 `basicAck`，消息从队列删除；处理失败 `basicNack(requeue=true)`，消息重新入队。

> 一条消息发给交换机、多个队列都匹配时，每个队列各得一份副本，彼此独立消费——这就是「发布/订阅」效果。项目用 Topic 交换机 + 相同路由键模拟了它。

---

## 三、四种交换机类型

| 类型 | 路由规则 | 典型场景 | 路由键示例 |
|---|---|---|---|
| direct | 路由键与绑定键**完全相等** | 按业务类型精确分发 | `order.created` |
| fanout | **忽略路由键**，广播给所有绑定队列 | 通知所有下游、缓存刷新 | 任意 |
| topic | 路由键与绑定**模式匹配**：`*` 匹配一个单词，`#` 匹配零或多个单词 | 按业务域分级订阅 | `user.*`、`order.#` |
| headers | 按消息头键值匹配（基本不用） | 复杂条件路由 | 不涉及 |

Topic 匹配练习（`user.registered` 这条消息）：

| 绑定键 | 是否匹配 | 原因 |
|---|---|---|
| `user.registered` | 是 | 完全一致 |
| `user.*` | 是 | `*` 正好匹配 `registered` 一个单词 |
| `user.#` | 是 | `#` 可匹配零个或多个单词 |
| `*.registered` | 是 | `*` 匹配 `user` |
| `user.registered.vip` | 否 | 模式更长，消息少一段 |
| `user` | 否 | 消息多一段 |

补充两个内置交换机：

- **默认交换机** `""`（direct）：路由键直接写队列名即可投递，简单场景用，生产建议显式声明交换机。
- **`amq.*` 系列**：RabbitMQ 内置（如 `amq.direct`），一般不直接用。

---

## 四、项目代码逐层拆解

模块路径：`src/main/java/com/demo/rabbitmq/`

### 1. 拓扑声明：RabbitMqConfig

```java
@Bean
public TopicExchange userExchange() {
    return new TopicExchange(USER_EXCHANGE, true, false); // durable=true, autoDelete=false
}

@Bean
public Queue emailQueue() {
    return QueueBuilder.durable(EMAIL_QUEUE).build();
}

@Bean
public Binding emailBinding(Queue emailQueue, TopicExchange userExchange) {
    return BindingBuilder.bind(emailQueue).to(userExchange).with(ROUTING_KEY_USER_REGISTERED);
}
```

要点：

- 交换机、队列都声明为 **durable（持久化）**，Broker 重启后拓扑还在；消息本身默认 `deliveryMode=PERSISTENT`，也会落盘。
- 声明是**幂等**的：队列已存在且参数一致就复用，参数不一致会报 `406 PRECONDITION_FAILED`，需先删旧队列（见命令文档「改队列参数报 406」）。
- 绑定是「交换机 → 队列」的关系，必须显式声明；只在管理台手动绑定、代码里不声明，换环境就会漏。

### 2. 消息转换器

```java
@Bean
public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
}
```

默认转换器是 Java 序列化，消息体不可读且跨语言困难。统一用 Jackson JSON，生产者和消费者必须用同一个转换器，否则反序列化失败（常见报错 `MessageConversionException`）。

> Spring Boot 会自动把容器里的 `MessageConverter` Bean 装配给 `RabbitTemplate`，但 `@RabbitListener` 的转换器在 2.x 中同样会取用该 Bean，因此项目只声明一个即可。

### 3. 生产者：UserEventProducer

```java
String msgId = UUID.randomUUID().toString().replace("-", "");
CorrelationData correlationData = new CorrelationData(msgId);

rabbitTemplate.convertAndSend(
        USER_EXCHANGE,
        ROUTING_KEY_USER_REGISTERED,
        event,
        message -> {
            message.getMessageProperties().setMessageId(msgId);
            return message;
        },
        correlationData);
```

两个 ID 别混淆：

| 字段 | 作用 | 放在哪 |
|---|---|---|
| `msgId` | 业务幂等键，消费者用它去重 | `MessageProperties.messageId`，随消息走 |
| `CorrelationData.id` | 把 Confirm 回调和某次发送关联起来 | 仅生产者本地，不随消息走 |

`convertAndSend` 的 `MessagePostProcessor` 用于在发送前修改消息属性（这里写 msgId）。

### 4. 消费者：EmailConsumer / PointsConsumer

```java
@RabbitListener(queues = RabbitMqConfig.EMAIL_QUEUE)
public void handleEmail(UserRegisteredEvent event, Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    String msgId = message.getMessageProperties().getMessageId();
    ...
    channel.basicAck(deliveryTag, false);
}
```

- `acknowledge-mode=manual` 下，Spring 不再自动 ACK，必须自己调用 `basicAck` / `basicNack`。
- `deliveryTag`：当前 Channel 上的投递序号，ACK 时必须带上；`multiple=false` 表示只确认这一条。
- `basicNack(tag, false, true)`：拒绝并重新入队；`requeue=false` 且配置了死信交换机时，消息会进死信队列。

两个消费者的分工：

| | EmailConsumer | PointsConsumer |
|---|---|---|
| 队列 | `email.queue` | `points.queue` |
| 是否模拟失败 | 否 | 前 2 次抛异常 + Nack 重入队，第 3 次成功 |
| 演示目的 | 正常 ACK 流程 | 重试流程、幂等兜底 |

### 5. 业务侧：UserService / UserController

```java
// 1. 写库（模拟）
// 2. 发事件到 MQ
String msgId = userEventProducer.sendUserRegistered(event);
```

注册接口只做「写库 + 发消息」，不发邮件、不算积分，主流程快速返回。这就是**异步解耦**：下游新增一个「发优惠券」消费者，注册代码一行都不用改。

---

## 五、可靠性三环节（消息不丢）

一条消息可能丢在三个地方，项目对每个环节都有对应措施。

### 环节 1：生产者 → Broker

**问题**：网络抖动、Broker 未落盘，生产者以为发成功了。

**手段**：

1. **Publisher Confirm（发布确认）**：Broker 收到消息后回调 `ConfirmCallback`，`ack=true` 表示已接收（投递到目标队列或持久化，取决于配置）；`ack=false` 表示 Broker 内部异常，消息可能丢了。
2. **Publisher Return（不可路由退回）**：交换机找不到匹配队列时，配置 `mandatory=true` 才回调 `ReturnsCallback`，否则消息被静默丢弃。

项目配置（`application.properties`）：

```properties
spring.rabbitmq.publisher-confirm-type=correlated
spring.rabbitmq.publisher-returns=true
spring.rabbitmq.template.mandatory=true
```

代码在 `RabbitMqConfig#rabbitTemplate` 中设置 `setConfirmCallback` 与 `setReturnsCallback`。

要注意：**Confirm 只证明 Broker 收下了，不证明消费者消费成功**。业务上的「已处理」靠消费者 ACK 与幂等表。

### 环节 2：Broker 内部

**问题**：Broker 重启，内存里的消息没了。

**手段**：交换机 durable + 队列 durable + 消息 persistent，三者缺一不可。项目三者都满足。

### 环节 3：Broker → 消费者

**问题**：消费者处理到一半宕机，消息是否还在？

**手段**：手动 ACK。

- 处理成功 → `basicAck` → 消息删除。
- 处理失败 → `basicNack(requeue=true)` → 消息回队列，重新投递。
- 消费者进程崩溃（未 ACK 就断开）→ Broker 把该消息重新置为 ready，投给其他消费者。

因此消费端必须做到**幂等**（见第九节），因为消息可能被投递多次。

---

## 六、消息 TTL 与队列过期

TTL（Time To Live，存活时间）控制消息或队列的存活时长，是延迟任务、重试阶梯和防止消息堆积的基础。

### 1. 三种 TTL 别混

| 类型 | 设置位置 | 参数 | 作用范围 | 到期后 |
|---|---|---|---|---|
| 消息 TTL（per-message） | 每条消息的消息属性 | `expiration` | 只作用于该条消息 | 丢弃；队列配了 DLX 则转死信 |
| 队列 TTL（per-queue） | 队列声明参数 | `x-message-ttl` | 队列内所有消息 | 丢弃；队列配了 DLX 则转死信 |
| 队列过期（queue expiration） | 队列声明参数 | `x-expires` | 整个队列 | 队列及其中消息一起删除，**不触发死信** |

关键规则：

- 消息同时受队列 TTL 和自身 `expiration` 约束时，**取较小的那个**。
- 消息 TTL **不是在到期瞬间删除**：classic 队列在消息到达**队首**时才判断是否过期。若队首消息 TTL 很长，后面的消息即使已过期也要排队等它先出队。
- `x-expires` 计算的是队列**空闲时长**（没有消费者、没有 basic.get、没有重新声明），适合临时队列；长期业务队列不要设。
- 修改队列的 TTL / DLX / 队列类型等参数，必须先删旧队列再重建，否则启动报 `406 PRECONDITION_FAILED`。

### 2. 声明方式（Spring AMQP）

```java
// 队列级 TTL：队列内消息最多活 60 秒；队列空闲 30 分钟自动删除；最多存 1 万条
Queue ttlQueue = QueueBuilder.durable("points.ttl.queue")
        .ttl(60_000)                 // x-message-ttl，单位毫秒
        .expires(1_800_000)          // x-expires，单位毫秒（30 分钟）
        .maxLength(10_000)           // x-max-length，超出按 x-overflow 处理
        .build();

// 消息级 TTL：发送时逐条设置（单位毫秒，注意是字符串）
rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event, message -> {
    message.getMessageProperties().setExpiration("5000"); // 5 秒后过期
    return message;
});
```

命令行与管理台：

```bash
# 查看队列上生效的参数
docker exec -it rabbitmq-learning rabbitmqctl list_queues name arguments

# 用策略批量设置 TTL（比逐个队列声明参数更适合运维调整）
docker exec -it rabbitmq-learning rabbitmqctl set_policy ttl "^points\." '{"message-ttl":60000}' --apply-to queues
```

> `expiration` 必须是**毫秒数字的字符串**（如 `"5000"`），写成数字或 `"5s"` 都不生效；`x-message-ttl` 则是数字类型的队列参数。**不设置才表示不过期**，设为 0 表示消息只有在消费者可立即投递时才能存活，否则马上过期，不要用 0 代表"永不过期"。

### 3. 典型用途与常见坑

| 用途 | 做法 |
|---|---|
| 延迟消息 | 队列 TTL + DLX 把消息转到目标队列（见第八节） |
| 防止队列无限堆积 | 队列 TTL / 最大长度 + 死信，堆积时保护 Broker 磁盘 |
| 临时队列自动清理 | `x-expires`，无人使用后自动删除 |
| 订单未支付超时 | 延迟队列，到期后由消费者执行关单 |

- **同队列混用不同 TTL 会阻塞**：队首消息未过期时，后面已过期的消息不会被清理。多级延迟必须**按延迟时长拆成多个队列**。
- **消息过期没有"通知"**：只是被移除，没有任何消费者会收到；要处理超时业务必须配置 DLX。
- **`x-expires` 删除队列后消息直接消失**，不会进死信，也不能恢复。

---

## 七、死信队列（DLX / DLQ）

「死信」指无法被正常消费的消息。死信交换机（DLX）和死信队列（DLQ）本身没有任何特殊类型，就是**普通 exchange + 普通 queue**，只是被业务队列通过 `x-dead-letter-*` 参数挂接。

### 1. 什么时候产生死信

| 触发条件 | 说明 |
|---|---|
| 消费者拒绝且不重回队列 | `basicNack(tag, false, false)` 或 `basicReject(tag, false)` |
| 消息 TTL 过期 | 队列 TTL 或消息 `expiration` 到期，且队列配置了 DLX |
| 队列达到最大长度 | `x-max-length` / `x-max-length-bytes` 溢出，按 `x-overflow` 策略把队首消息转死信（默认 `drop-head`） |
| 投递次数超限（quorum 队列） | `x-delivery-limit` 达到上限（RabbitMQ 3.8+） |

注意：`basicNack(tag, false, true)`（requeue=true）**不会产生死信**，只会反复投递。

### 2. 队列上的死信相关参数

| 参数 | 作用 |
|---|---|
| `x-dead-letter-exchange` | 死信投递到哪个交换机；不配 routing key 时沿用消息原来的 routing key |
| `x-dead-letter-routing-key` | 重写死信的 routing key（建议显式指定，避免与原绑定冲突） |
| `x-max-length` / `x-max-length-bytes` | 队列长度 / 总字节上限 |
| `x-overflow` | `drop-head`（默认，丢弃队首并转死信）/ `reject-publish`（拒绝新消息，新消息未入队故不会死信；quorum 队列可用 `reject-publish-dlx` 转死信） |
| `x-message-ttl` | 消息在队列内的最长存活时间 |

### 3. 完整拓扑声明

```
biz.exchange ──user.registered──> user.queue
                                     │ nack(requeue=false) / TTL 过期 / 超长
                                     v
                                  user.dlx ──user.dead──> user.dlq（告警、落库、人工重放）
```

```java
// 死信交换机（普通 Topic 交换机）
@Bean
public TopicExchange userDlx() {
    return new TopicExchange("user.dlx", true, false);
}

// 死信队列
@Bean
public Queue userDlq() {
    return QueueBuilder.durable("user.dlq").build();
}

// 死信队列绑定
@Bean
public Binding userDlqBinding(Queue userDlq, TopicExchange userDlx) {
    return BindingBuilder.bind(userDlq).to(userDlx).with("user.dead");
}

// 业务队列挂上死信参数（同时可加 TTL / 长度上限）
@Bean
public Queue pointsQueue() {
    return QueueBuilder.durable("points.queue")
            .deadLetterExchange("user.dlx")
            .deadLetterRoutingKey("user.dead")
            .ttl(30_000)          // 30 秒未消费也转死信
            .maxLength(10_000)    // 超过 1 万条按 x-overflow 处理
            .build();
}
```

> 若 `points.queue` 已存在且参数不同，先删旧队列再让应用重建，否则报 406。

### 4. 死信消息的结构

消息体和属性（含 `messageId`）原样保留，Broker 追加 `x-death` 头，记录每次死信的来源与原因：

```json
"x-death": [
  {
    "count": 1,
    "reason": "rejected",
    "queue": "points.queue",
    "exchange": "user.exchange",
    "routing-keys": ["user.registered"],
    "time": "2026-11-03 10:00:00"
  }
]
```

| 字段 | 含义 |
|---|---|
| `reason` | `rejected`（被 nack/reject）、`expired`（TTL 过期）、`maxlen`（超长） |
| `count` | 在同一队列、同一原因下的累计死信次数，可作为重试次数依据 |
| `queue` | 死信来自哪个队列 |
| `exchange` / `routing-keys` | 原始交换机与路由键 |

另有 `x-first-death-reason`、`x-first-death-queue`、`x-first-death-exchange`，记录**第一次**死信的信息。注意 `x-death` 是数组，多次死信会有多条记录。

### 5. 死信消费类（告警 / 落库 / 人工重放）

```java
@Component
public class DeadLetterConsumer {

    @RabbitListener(queues = "user.dlq")
    public void handleDead(UserRegisteredEvent event, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        List<?> deaths = (List<?>) message.getMessageProperties().getHeaders().get("x-death");

        // 生产环境：写死信表 + 告警，不要再次 requeue 造成死循环
        System.err.println("[DLQ] 收到死信, msgId=" + message.getMessageProperties().getMessageId()
                + ", x-death=" + deaths);

        channel.basicAck(tag, false); // 已落库/告警，确认移除
    }
}
```

死信的三种处理方式：

| 方式 | 适用场景 | 注意 |
|---|---|---|
| 告警 + 人工重放 | 毒消息、需要人工修复的业务 | 重放要重新生成 msgId 或沿用幂等键，避免重复 |
| 定时任务重放 | 下游短时不可用导致的批量死信 | 重放次数仍需上限，防止再次死循环 |
| 直接丢弃 | 无业务价值、可容忍丢失的消息 | 至少记录日志和监控指标 |

### 6. 使用死信队列的坑

- **DLQ 必须有监控**：没有消费者的 DLQ 会静默堆积，必须配置堆积量告警；也可给 DLQ 设 TTL / 长度上限兜底。
- **避免死信循环**：DLQ 的绑定不要把消息路由回业务队列；DLX 与业务交换机要分开。
- **死信 ≠ 重试**：进入 DLQ 的消息默认不会再被自动处理，重试阶梯要靠 TTL 队列把消息"转回"业务队列。
- **参数不可改**：DLX、TTL、长度上限都是队列声明参数，变更需删队列重建，生产要提前规划。
- **顺序不保证**：死信进入 DLQ 的顺序不等于原始发送顺序。

---

## 八、失败重试与延迟队列（项目现状 + 生产改法）

### 项目现状

`PointsConsumer` 前 2 次 `basicNack(requeue=true)`，第 3 次成功。配合 `prefetch=1`、单消费者，日志能清楚看到同一条 msgId 被消费三次。

**风险**：`requeue=true` 且没有次数上限，等于**无限重试**。如果消息本身有问题（如字段缺失导致必然异常），会形成「失败 → 入队 → 立刻再投递」的忙循环，占满 CPU 和日志。

### 方案一：TTL + DLX 实现阶梯延迟重试（经典队列常用）

原理：业务队列处理失败后 `nack(requeue=false)` 进入 DLX，DLX 把消息路由到「重试队列」；重试队列设置 TTL 且自身 DLX 指回业务交换机，消息过期后被重新投递回业务队列，从而实现「等待 N 秒后再试」。

```
points.queue ──失败 nack(requeue=false)──> retry.exchange ──> points.retry.queue（TTL=5s）
     ^                                                              │ 到期后 DLX
     └──────────── points.retry.exchange / points.retry ◄──────────┘
```

> 注意：重试队列的 DLX 要指回**只绑定积分业务的交换机**，不能直接指回 `user.exchange`，否则一次重试会再次广播给 `email.queue`，导致欢迎邮件重复发送。

以下为示意配置，实际落地时需要改造 `RabbitMqConfig#pointsQueue`，并新增重试交换机、重试队列及对应绑定：

```java
// 1) 失败后进入的重试交换机：只负责把消息放进 TTL 队列
@Bean
public TopicExchange retryExchange() {
    return new TopicExchange("retry.exchange", true, false);
}

// 2) 业务队列：失败时转 retry.exchange
@Bean
public Queue pointsQueue() {
    return QueueBuilder.durable("points.queue")
            .deadLetterExchange("retry.exchange")
            .deadLetterRoutingKey("points.retry")
            .build();
}

@Bean
public Binding pointsRetryQueueBinding(Queue pointsRetryQueue, TopicExchange retryExchange) {
    return BindingBuilder.bind(pointsRetryQueue).to(retryExchange).with("points.retry");
}

// 3) TTL 重试队列：5 秒后转回积分业务交换机
@Bean
public Queue pointsRetryQueue() {
    return QueueBuilder.durable("points.retry.queue")
            .ttl(5_000)
            .deadLetterExchange("points.retry.exchange")
            .deadLetterRoutingKey("points.retry")
            .build();
}

// 4) 积分业务交换机：只入 points.queue，重试回来不会广播到 email.queue
@Bean
public TopicExchange pointsRetryExchange() {
    return new TopicExchange("points.retry.exchange", true, false);
}

@Bean
public Binding pointsQueueRetryBinding(Queue pointsQueue, TopicExchange pointsRetryExchange) {
    return BindingBuilder.bind(pointsQueue).to(pointsRetryExchange).with("points.retry");
}
```

**重试次数限制**（否则仍是无限循环）：消费者从 `x-death` 取 `count`，超过阈值时不再 nack 回重试队列，而是主动投递到最终 DLQ 后 ACK：

```java
int deadCount = getDeathCount(message); // 统计 x-death 中与当前业务队列相关的次数，缺失按 0
if (deadCount >= MAX_RETRY) {
    rabbitTemplate.convertAndSend("user.dlx", "user.dead", event); // 转最终死信
    channel.basicAck(deliveryTag, false);
    return;
}
channel.basicNack(deliveryTag, false, false); // 进入 TTL 重试队列
```

> `x-death[0]` 只是最近一次死信记录，且每次经过不同队列/原因都会新增条目。统计总重试次数时应遍历数组、按 `queue` 与 `reason` 筛选后求和，直接取下标 0 的 `count` 容易误判。

多级退避（5s → 30s → 1min）就声明多个 TTL 不同的重试队列；**不能在同一队列里混用不同 TTL**（队首阻塞问题见第六节）。

### 方案二：延迟交换机插件

安装 `rabbitmq_delayed_message_exchange` 后声明 `x-delayed-message` 类型交换机，发送时设置 `x-delay` 头即可实现任意延迟，无需为每档延迟建队列：

```java
Map<String, Object> args = new HashMap<>();
args.put("x-delayed-type", "topic");
new CustomExchange("delay.exchange", "x-delayed-message", true, false, args);

// 发送时指定延迟毫秒
message.getMessageProperties().setHeader("x-delay", 30_000);
```

缺点：插件需要额外安装、消息落盘行为与普通交换机不同，部分托管云服务不开放插件安装。

### 方案三：应用内重试（Spring Retry）

```properties
spring.rabbitmq.listener.simple.retry.enabled=true
spring.rabbitmq.listener.simple.retry.max-attempts=3
spring.rabbitmq.listener.simple.retry.initial-interval=1000ms
spring.rabbitmq.listener.simple.retry.multiplier=2
# 重试耗尽后不再 requeue，交给队列的 DLX 进入死信
spring.rabbitmq.listener.simple.default-requeue-rejected=false
```

适合下游瞬时抖动（连接超时、短暂锁冲突），重试发生在同一进程内，不产生跨队列流量；但进程崩溃会丢失本轮重试计数。

### 选择原则

| 场景 | 推荐方案 |
|---|---|
| 下游瞬时故障，重试间隔短 | 方案三：应用内 Spring Retry |
| 需要 5s/30s/1min 阶梯退避 | 方案一 TTL + DLX，或方案二延迟插件 |
| 毒消息兜底 | 最终 DLQ + 告警 + 人工/定时重放 |
| 严格顺序业务 | 单队列单消费者，重试串行化，避免并行消费打乱顺序 |

---

## 九、幂等消费

消息至少投递一次（at-least-once），重复不可避免。项目用 `msgId` 去重：

```java
if (idempotencyStore.isProcessed(msgId)) {
    channel.basicAck(deliveryTag, false);
    return;
}
...
idempotencyStore.markProcessed(msgId);
channel.basicAck(deliveryTag, false);
```

`IdempotencyStore` 用 `ConcurrentHashMap` 模拟，**仅在单机 Demo 可用**。生产要换成 Redis：

```java
// 消费前占位：不存在才写入，成功返回 true
Boolean first = redisTemplate.opsForValue().setIfAbsent("mq:id:" + msgId, "1", Duration.ofHours(24));
if (Boolean.FALSE.equals(first)) {
    // 已处理过，直接 ACK
}
```

注意时序：**先占位再执行业务**，业务失败要删除占位，否则消息重试时会被误判为「已处理」。或者「业务成功后再写幂等表」，此时窗口内重复投递仍有并发风险，需要业务唯一约束兜底（如 `user_id` 唯一索引）。

幂等 key 的选择：

| 方案 | 优点 | 缺点 |
|---|---|---|
| `msgId` | 通用、与业务无关 | 生产者必须保证唯一 |
| 业务唯一键（订单号） | 天然去重，跨消息也有效 | 需要业务字段支持 |
| 数据库唯一索引 | 最强兜底 | 依赖 DB，冲突时要处理异常 |

幂等是**业务语义**，不是 MQ 提供的功能。MQ 只能保证「至少一次」，去重必须自己做。

---

## 十、消费端关键配置：prefetch 与并发

```properties
spring.rabbitmq.listener.simple.acknowledge-mode=manual
spring.rabbitmq.listener.simple.prefetch=1
spring.rabbitmq.listener.simple.concurrency=1
spring.rabbitmq.listener.simple.max-concurrency=1
```

- `prefetch`：每个消费者最多预取多少条未 ACK 消息。设 1 表示「处理完一条才拿下一批」，适合慢任务、需要顺序的场景；设太大则单消费者囤积消息，宕机时大量消息重新投递，且多消费者间负载不均。
- `concurrency` / `max-concurrency`：消费者线程数。项目设为 1，是为了让重试顺序和日志可观察。
- **顺序性**：单队列 + 单消费者 + prefetch 可保证局部有序；一旦多消费者并行，顺序即无法保证。需要严格顺序时按业务键哈希到不同队列（如订单 ID 取模），每个队列单消费者。

---

## 十一、动手练习

### 练习 0：启动环境

```bash
docker run -d --name rabbitmq-learning \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:3.13-management
```

管理台：`http://localhost:15672`，账号密码 `guest / guest`。

> guest 默认只允许 localhost 登录，本机开发没问题；远程访问需新建用户并授权，见命令文档第四节。

### 练习 1：启动 Demo 应用

```bash
mvn spring-boot:run -Dspring-boot.run.mainClass=com.demo.rabbitmq.RabbitMqApplication
```

也可在 IDE 直接运行 `RabbitMqApplication#main`。注意模块端口是 8080，与 jwt 模块冲突，一次只启动一个。

### 练习 2：触发一次注册

```bash
curl -X POST "http://localhost:8080/users/register?username=alice&email=alice@demo.com"
```

预期日志顺序（同一 msgId）：

```
[Producer] 已发送用户注册事件, msgId=xxx
[Publisher-Confirm] 消息已被 Broker 确认接收, msgId=xxx
[Email-Consumer] 欢迎邮件发送成功 ...
[Points-Consumer] 第 1 次消费失败(模拟异常) ...
[Points-Consumer] 第 2 次消费失败(模拟异常) ...
[Points-Consumer] 用户积分初始化成功 ...
```

观察点：

- 一条发送，两个队列各消费一次 → 发布/订阅；
- Confirm 回调证明 Broker 已接收；
- 积分消费者重试 3 次才成功，但幂等表保证只初始化一次。

### 练习 3：管理台验证拓扑

进入 15672：

1. **Exchanges** → `user.exchange`，类型 `topic`，Bindings 里能看到两个队列。
2. **Queues** → 消息处理完后 `Ready` 和 `Unacked` 都应为 0。
3. 在 Queues 里点 `Get messages`，Ack mode 选 `Nack requeue=true`，可在不取走消息的情况下偷看消息体。

### 练习 4：验证持久化

```bash
# 先停应用，避免消息被消费掉；或在管理台 Publish 一条消息到空队列
docker restart rabbitmq-learning
```

重启后刷新管理台：交换机、队列仍在，未消费消息仍在。若队列非 durable，重启后队列消失，消息一并丢失。

### 练习 5：验证不可路由退回

临时把 `RabbitMqConfig` 中的路由键改成 `user.unknown`（或新增一个不匹配的绑定），重启应用再注册。

预期：管理台队列无新消息，日志出现：

```
[Publisher-Return] 消息不可路由被退回: replyCode=312, replyText=NO_ROUTE ...
```

这说明 `mandatory=true` + ReturnsCallback 生效；去掉 mandatory，消息会被静默丢弃、无任何提示。

### 练习 6：验证未 ACK 消息的归属

在 `EmailConsumer` 的 `basicAck` 之前打断点（或加 `Thread.sleep(60000)`），再注册一次，然后看：

```bash
docker exec -it rabbitmq-learning rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

预期 `messages_unacknowledged=1`。此时直接杀掉应用进程（不 ACK），消息会回到 `ready`，应用重启后会被重新消费——这就是「至少一次」的来源，也是必须幂等的原因。

### 练习 7：验证 TTL 过期与死信（只用管理台，不改代码）

1. **Exchanges** 新建 `user.dlx`（type=topic，durable=true）。
2. **Queues** 新建 `user.dlq`（durable=true），在 `user.dlx` 上添加绑定，routing key 填 `user.dead`。
3. **Queues** 新建 `points.ttl.queue`（durable=true），Arguments 填：

   | Key | Value |
   |---|---|
   | `x-message-ttl` | `5000` |
   | `x-dead-letter-exchange` | `user.dlx` |
   | `x-dead-letter-routing-key` | `user.dead` |

4. 进入 `points.ttl.queue` 的 **Publish message**，Routing key 填 `points.ttl.queue`（或直接发布到默认交换机），随便填一段 JSON。
5. 观察：`points.ttl.queue` 的 Ready 先变为 1，约 5 秒后归 0；`user.dlq` 变为 1。
6. 在 `user.dlq` 点 **Get messages**，Ack mode 选 `Nack requeue=true` 只查看不取走，展开消息头能看到 `x-death`，`reason=expired`。
7. 再向 `points.ttl.queue` 发一条消息，立刻用 **Get messages** 以 `Nack requeue=false` 取走，`user.dlq` 会再增加一条，这次 `x-death` 的 `reason=rejected`。

> 注意：`points.ttl.queue` 不要挂消费者，否则消息可能在到期前就被消费走；TTL 只在消息到达队首时判断，"到期"和"被删除"之间可能有延迟。

---

## 十二、常见问题排查

| 现象 | 优先检查 | 常见原因 |
|---|---|---|
| 应用启动报连接失败 | `rabbitmqctl list_connections`、5672 端口映射、账号密码 | 容器没起、端口没映射、guest 限制 |
| 消息发了但队列没消息 | 交换机名称、路由键、绑定关系（管理台 Exchanges → Bindings） | 路由键写错、绑定未声明 |
| 队列有消息但 `consumers=0` | `rabbitmqctl list_queues`、消费者日志 | `@RabbitListener` 队列名不一致、消费端启动失败 |
| 消息反复出现、CPU 高 | 日志中同一 msgId 多次失败 | `basicNack(requeue=true)` 无限重试 |
| 消息重复处理 | 幂等表 / Redis 占位逻辑 | 未做幂等，或占位时序错误 |
| `406 PRECONDITION_FAILED` | 队列参数是否变过（DLX、TTL、类型） | 先 Delete 旧队列再重建 |
| `MessageConversionException` | 收发两端转换器是否一致 | 一端 Java 序列化、一端 JSON |
| 消息到点没被处理、直接消失 | 队列是否有 TTL / DLX，`expiration` 写法 | TTL 到期被删除；`expiration` 不是毫秒字符串 |
| 配了 DLX 但死信队列没消息 | 队列的 `x-dead-letter-exchange`、DLX 绑定、nack 参数 | 队列参数未生效（406 后没重建）；nack 时误传 requeue=true；routing key 不匹配 |
| 队列莫名其妙消失 | 是否设置 `x-expires` 策略 | 队列空闲超时被自动删除，消息一并丢失 |
| 消费很慢、消息堆积 | `prefetch`、消费者线程数、下游耗时 | prefetch 过大或下游阻塞 |

排查顺序建议：**先看连接 → 再看交换机绑定 → 再看队列三项指标（ready / unacked / consumers）→ 最后看消费者日志**。

---

## 十三、自测题

1. 消息是发给交换机还是队列？交换机如何知道该投给哪些队列？
2. Topic 交换机中 `*` 与 `#` 的区别？绑定键 `order.#` 能否匹配 `order`？
3. 交换机 durable、队列 durable、消息 persistent，少一个会怎样？
4. ConfirmCallback 返回 ack=true，是否代表消费者已成功处理？为什么？
5. 为什么 `basicNack(requeue=true)` 不能作为最终失败处理？生产应该怎么做？
6. 消费端幂等应该在业务执行前还是执行后占位？各自的风险是什么？
7. 三个消费者订阅同一个队列，能否保证消息按发送顺序被处理？如何实现严格顺序？
8. `prefetch=1` 与 `prefetch=20` 分别适合什么场景？
9. `x-message-ttl` 与消息属性 `expiration` 有什么区别？同时设置时哪个生效？
10. `x-expires` 和 `x-message-ttl` 分别控制什么？队列过期会让消息进死信吗？
11. 死信的触发条件有哪些？`basicNack(requeue=true)` 会产生死信吗？
12. 如何利用 `x-death` 限制重试次数？为什么不能只靠 DLQ 做重试？
13. 用 TTL + DLX 做延迟重试时，为什么不同延迟要拆多个队列？

参考答案可在文档各章节中找到；面试时重点讲清「可靠性三环节」和「幂等 + 死信」。

---

## 十四、生产实践清单

- [ ] 交换机、队列、绑定全部代码声明，禁止只在管理台手工创建。
- [ ] 关键业务开启 Publisher Confirm + Returns，并对 `ack=false`、Return 做补偿（落库重发 / 告警）。
- [ ] 消息统一 JSON，带 `messageId` 与业务唯一键，便于幂等和追踪。
- [ ] 消费端手动 ACK，失败进入重试策略，超过次数转死信队列并告警。
- [ ] 业务队列显式配置 DLX / DLQ，死信表落库并配置堆积量告警；DLQ 自身设 TTL 或长度上限兜底。
- [ ] 阶梯重试按延迟时长拆多个 TTL 队列，禁止在同一队列混用不同 TTL。
- [ ] 消息级 `expiration` 使用毫秒字符串；TTL、DLX 等队列参数变更走「删队列 + 重建」流程。
- [ ] 幂等表用 Redis 或 DB 唯一约束，设置合理过期时间。
- [ ] 慢任务调小 prefetch，快任务可适当调大；消费者并发按下游容量评估。
- [ ] 监控队列堆积、未 ACK 数、消费者数量，设置阈值告警。
- [ ] 生产环境独立 vhost 与最小权限账号，禁用 guest。
- [ ] 队列与消息量增长前评估磁盘与内存水位，配置 `vm_memory_high_watermark` 和磁盘告警。

---

## 十五、术语速查

| 术语 | 含义 |
|---|---|
| AMQP | RabbitMQ 实现的协议标准，定义交换机、队列、绑定等模型 |
| Broker | RabbitMQ 服务端进程 |
| vhost | 虚拟主机，隔离交换机/队列/权限 |
| exchange | 交换机，负责路由 |
| queue | 队列，负责存储 |
| binding | 绑定，交换机到队列的路由规则 |
| routing key | 生产者指定的路由标签 |
| delivery tag | Channel 内的投递序号，ACK 时使用 |
| ACK / NACK | 确认 / 否认，决定消息删除还是重投 |
| prefetch | 消费者未 ACK 消息的最大预取数 |
| DLX / DLQ | 死信交换机 / 死信队列 |
| TTL | 消息或队列的存活时间（Time To Live） |
| expiration | 消息级 TTL 属性，毫秒数字的字符串 |
| x-message-ttl | 队列级消息 TTL 参数（毫秒） |
| x-expires | 队列空闲过期时间，到期删除整个队列 |
| x-overflow | 队列超长溢出策略：drop-head / reject-publish |
| x-death | Broker 追加的死信记录头，含 reason、count 等 |
| 延迟队列 | 借助 TTL + DLX 或延迟交换机插件实现的定时投递 |
| Publisher Confirm | Broker 对生产者的接收确认 |
| Publisher Return | 消息不可路由时退回给生产者 |
| at-least-once | 至少一次投递，重复需业务幂等 |
