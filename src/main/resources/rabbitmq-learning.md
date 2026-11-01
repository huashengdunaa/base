# RabbitMQ 学习文档（Java 初级开发向）

面向 Java 初级开发，以项目中的 `com.demo.rabbitmq` 模块为样本，讲清 RabbitMQ 的核心概念、消息流转、可靠性保障和常见问题。
命令速查见同目录 [rabbitmq-commands.md](rabbitmq-commands.md)。

学习完成后应能：

- 说清 交换机 / 队列 / 绑定 / 路由键 的关系；
- 独立设计一个「用户注册 → 异步发邮件 + 初始化积分」的 MQ 拓扑；
- 理解并实现 发布确认、持久化、手动 ACK、幂等消费；
- 遇到「消息没消费」「消息重复」「消息丢失」时知道从哪查。

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

因此消费端必须做到**幂等**（见下一节），因为消息可能被投递多次。

---

## 六、重试与死信（项目现状 + 生产改法）

### 项目现状

`PointsConsumer` 前 2 次 `basicNack(requeue=true)`，第 3 次成功。配合 `prefetch=1`、单消费者，日志能清楚看到同一条 msgId 被消费三次。

**风险**：`requeue=true` 且没有次数上限，等于**无限重试**。如果消息本身有问题（如字段缺失导致必然异常），会形成「失败 → 入队 → 立刻再投递」的忙循环，占满 CPU 和日志。

### 生产标准做法

1. 用消息头 `x-death` 或业务表记录重试次数，超过阈值不再 requeue。
2. 拒绝时 `basicNack(tag, false, false)`，让消息进入**死信队列（DLQ）**。
3. 声明队列时挂上死信参数：

```java
Map<String, Object> args = new HashMap<>();
args.put("x-dead-letter-exchange", "user.dlx");
args.put("x-dead-letter-routing-key", "user.dead");
Queue queue = QueueBuilder.durable(POINTS_QUEUE).withArguments(args).build();
```

4. 需要「延迟重试」时用两种方案：
   - 死信 + TTL：`x-message-ttl` 或消息级 `expiration`，过期后进 DLX，再由另一个队列消费，实现 5s/30s/1min 阶梯重试；
   - `rabbitmq_delayed_message_exchange` 插件：延迟交换机直接支持任意延迟，需先安装插件。

选择原则：**能立即重试的用应用内重试（Spring Retry），跨进程/长间隔的用 TTL + DLX 或延迟插件**。

> 触发死信有三种情况：消息被 `basicNack/basicReject(requeue=false)`、消息 TTL 过期、队列长度超限。

---

## 七、幂等消费

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

## 八、消费端关键配置：prefetch 与并发

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

## 九、动手练习

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

---

## 十、常见问题排查

| 现象 | 优先检查 | 常见原因 |
|---|---|---|
| 应用启动报连接失败 | `rabbitmqctl list_connections`、5672 端口映射、账号密码 | 容器没起、端口没映射、guest 限制 |
| 消息发了但队列没消息 | 交换机名称、路由键、绑定关系（管理台 Exchanges → Bindings） | 路由键写错、绑定未声明 |
| 队列有消息但 `consumers=0` | `rabbitmqctl list_queues`、消费者日志 | `@RabbitListener` 队列名不一致、消费端启动失败 |
| 消息反复出现、CPU 高 | 日志中同一 msgId 多次失败 | `basicNack(requeue=true)` 无限重试 |
| 消息重复处理 | 幂等表 / Redis 占位逻辑 | 未做幂等，或占位时序错误 |
| `406 PRECONDITION_FAILED` | 队列参数是否变过（DLX、TTL、类型） | 先 Delete 旧队列再重建 |
| `MessageConversionException` | 收发两端转换器是否一致 | 一端 Java 序列化、一端 JSON |
| 消费很慢、消息堆积 | `prefetch`、消费者线程数、下游耗时 | prefetch 过大或下游阻塞 |

排查顺序建议：**先看连接 → 再看交换机绑定 → 再看队列三项指标（ready / unacked / consumers）→ 最后看消费者日志**。

---

## 十一、自测题

1. 消息是发给交换机还是队列？交换机如何知道该投给哪些队列？
2. Topic 交换机中 `*` 与 `#` 的区别？绑定键 `order.#` 能否匹配 `order`？
3. 交换机 durable、队列 durable、消息 persistent，少一个会怎样？
4. ConfirmCallback 返回 ack=true，是否代表消费者已成功处理？为什么？
5. 为什么 `basicNack(requeue=true)` 不能作为最终失败处理？生产应该怎么做？
6. 消费端幂等应该在业务执行前还是执行后占位？各自的风险是什么？
7. 三个消费者订阅同一个队列，能否保证消息按发送顺序被处理？如何实现严格顺序？
8. `prefetch=1` 与 `prefetch=20` 分别适合什么场景？

参考答案可在文档各章节中找到；面试时重点讲清「可靠性三环节」和「幂等 + 死信」。

---

## 十二、生产实践清单

- [ ] 交换机、队列、绑定全部代码声明，禁止只在管理台手工创建。
- [ ] 关键业务开启 Publisher Confirm + Returns，并对 `ack=false`、Return 做补偿（落库重发 / 告警）。
- [ ] 消息统一 JSON，带 `messageId` 与业务唯一键，便于幂等和追踪。
- [ ] 消费端手动 ACK，失败进入重试策略，超过次数转死信队列并告警。
- [ ] 幂等表用 Redis 或 DB 唯一约束，设置合理过期时间。
- [ ] 慢任务调小 prefetch，快任务可适当调大；消费者并发按下游容量评估。
- [ ] 监控队列堆积、未 ACK 数、消费者数量，设置阈值告警。
- [ ] 生产环境独立 vhost 与最小权限账号，禁用 guest。
- [ ] 队列与消息量增长前评估磁盘与内存水位，配置 `vm_memory_high_watermark` 和磁盘告警。

---

## 十三、术语速查

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
| Publisher Confirm | Broker 对生产者的接收确认 |
| Publisher Return | 消息不可路由时退回给生产者 |
| at-least-once | 至少一次投递，重复需业务幂等 |
