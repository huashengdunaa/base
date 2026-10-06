# RabbitMQ 常用命令学习文档（Java 初级开发向）

基于 Docker 容器 `rabbitmq-learning`（镜像 rabbitmq:3.13-management）。

包含两类工具：

| 工具 | 用途 |
|---|---|
| `rabbitmqctl` | 服务端管理：节点状态、用户权限、vhost、队列/交换机/连接查看 |
| 管理台 UI（15672） | 可视化查看拓扑、消息、统计；学习期主要用它 |

另有 `rabbitmq-plugins` 管理插件。

---

## 一、命令执行方式

所有管理命令在容器内执行：

```bash
# 通用格式
docker exec -it rabbitmq-learning rabbitmqctl 子命令

# 管理台访问
# http://localhost:15672  guest / guest
```

---

## 二、节点与状态

```bash
# 节点整体状态（Erlang 版本、内存、文件句柄、监听端口等）
docker exec -it rabbitmq-learning rabbitmqctl status

# 健康检查（运维/脚本用，成功无输出，退出码 0）
docker exec -it rabbitmq-learning rabbitmqctl node_health_check

# 环境与运行时信息（精简）
docker exec -it rabbitmq-learning rabbitmqctl environment
```

---

## 三、查看运行时对象（排查最常用）

```bash
# 队列：名称、消息总数、就绪数、未 ACK 数、消费者数
docker exec -it rabbitmq-learning rabbitmqctl list_queues

# 带更多字段
docker exec -it rabbitmq-learning rabbitmqctl list_queues name messages messages_ready messages_unacknowledged consumers durable

# 交换机：名称、类型、是否持久化
docker exec -it rabbitmq-learning rabbitmqctl list_exchanges name type durable

# 绑定：来源交换机、目标队列、routingKey
docker exec -it rabbitmq-learning rabbitmqctl list_bindings source_name destination_key routing_key

# 当前 TCP 连接（应用没连上时先看这里）
docker exec -it rabbitmq-learning rabbitmqctl list_connections name peer_host peer_port state

# 信道（连接内的逻辑通道）
docker exec -it rabbitmq-learning rabbitmqctl list_channels connection consumer_count messages_unacknowledged

# 消费者：哪个队列、消费者 tag
docker exec -it rabbitmq-learning rabbitmqctl list_consumers queue_name consumer_tag
```

### 字段含义（对照练习观察）

| 字段 | 含义 |
|---|---|
| messages | 队列总消息数 = ready + unacknowledged |
| messages_ready | 等待投递给消费者的消息 |
| messages_unacknowledged | 已投递但未 ACK 的消息（消费者处理中） |
| consumers | 在线消费者数量，为 0 说明消费者没连上 |

练习 0 中 PointsConsumer 重试时，可观察消息在 ready 和 unacked 之间来回变化。

---

## 四、用户与权限

```bash
# 查看用户
docker exec -it rabbitmq-learning rabbitmqctl list_users

# 新增用户
docker exec -it rabbitmq-learning rabbitmqctl add_user app App@123456

# 改密码
docker exec -it rabbitmq-learning rabbitmqctl change_password app NewPwd@123

# 设置用户角色（administrator 可登录管理台）
docker exec -it rabbitmq-learning rabbitmqctl set_user_tags app administrator

# 授权：用户对 vhost 的 配置/写/读 权限（".*" 表示全部资源）
docker exec -it rabbitmq-learning rabbitmqctl set_permissions -p / app ".*" ".*" ".*"

# 查看权限
docker exec -it rabbitmq-learning rabbitmqctl list_permissions -p /

# 删除用户
docker exec -it rabbitmq-learning rabbitmqctl delete_user app
```

| 权限位 | 控制的操作 |
|---|---|
| configure | 声明/删除队列、交换机、绑定 |
| write | 发布消息 |
| read | 消费消息、清空队列 |

> guest 默认只能从 localhost 连接，容器内应用连本机可以；远程连接需新建用户。

### vhost（逻辑隔离，了解）

```bash
docker exec -it rabbitmq-learning rabbitmqctl add_vhost demo_vh
docker exec -it rabbitmq-learning rabbitmqctl list_vhosts
docker exec -it rabbitmq-learning rabbitmqctl delete_vhost demo_vh
```

应用连接串指定：`spring.rabbitmq.virtual-host=/`（默认即 `/`）。

---

## 五、插件管理

```bash
# 查看插件状态
docker exec -it rabbitmq-learning rabbitmq-plugins list

# 启用/禁用（management 镜像默认已启用管理台）
docker exec -it rabbitmq-learning rabbitmq-plugins enable rabbitmq_management
docker exec -it rabbitmq-learning rabbitmq-plugins disable rabbitmq_management
```

延迟消息插件 `rabbitmq_delayed_message_exchange` 需先下载 .ez 文件放进容器插件目录再 enable，学习阶段了解即可。

---

## 六、服务控制

```bash
# 容器环境一般用 docker 命令控制，不直接用 rabbitmqctl
docker stop rabbitmq-learning
docker start rabbitmq-learning
docker restart rabbitmq-learning

# 应用级停止/启动（节点进程还在，仅停 RabbitMQ 应用；排查集群时用）
docker exec -it rabbitmq-learning rabbitmqctl stop_app
docker exec -it rabbitmq-learning rabbitmqctl start_app
```

---

## 七、管理台 UI 常用操作（15672）

| 页签 | 能做什么 |
|---|---|
| Overview | 节点概览、端口、消息速率图表 |
| Connections | 查看/强制关闭应用连接 |
| Channels | 查看每个信道的 prefetch、unacked 数 |
| Exchanges | 查看交换机和绑定；发测试消息（Publish message） |
| Queues | 查看队列；绑定管理；手动取消息；发消息；清空/删除队列 |

### Queues 页签高频操作

| 操作 | 作用 | 注意 |
|---|---|---|
| Purge | 清空队列消息 | 消息直接删除，不可恢复 |
| Delete | 删除队列 | 改队列参数报 406 时先 Delete 再让应用重启重建 |
| Get messages | 手动拉取查看消息 | Ack mode 选 Nack requeue=true 可看而不取走 |
| Move messages / Dead letter | 转移消息到死信交换机 | 不同版本菜单位置略有差异 |

### 改队列参数报 406 的标准处理

1. Queues 页签选中旧队列 → Delete
2. 重启 Spring Boot 应用，按新参数（如 DLX、TTL）重新声明
3. Exchanges/Queues 确认新参数生效

---

## 八、Java 开发高频排查场景

| 场景 | 手段 |
|---|---|
| 应用启动报连接失败 | `list_connections` 有没有连接；检查 5672 映射和账号密码 |
| 消息发了没消费 | `list_queues` 看 consumers 是否为 0、ready 是否堆积 |
| 消费者卡住 | 看 messages_unacknowledged 数量，对应消费者是否阻塞未 ACK |
| routingKey 写错 | Exchanges 页查看 Bindings；确认 publisher-returns 日志 |
| 怀疑消息进了死信 | 查 points.dlq 队列消息数，Get messages 看 `x-death` 头 |
| 验证竞争消费/prefetch | Channels 页看每个信道的 prefetch_count 和 unacked 分布 |
| 验证持久化 | 重启容器后 `list_queues` 确认队列和消息是否还在 |

---

## 九、注意事项

- 命令行重对象（队列、交换机）名称区分大小写，与代码常量保持一致。
- `list_queues` 看到的 messages 是瞬时快照，排查堆积时多刷几次或看管理台图表。
- 不要在生产环境随意 Purge/Delete，操作前确认 vhost 和环境。
- 未 ACK 的消息不会丢失：消费者断开后，RabbitMQ 会把消息重新置为 ready 投递给其他消费者。
