# Java 线程池示例文档

> 包路径：`com.demo.threadpool`
> JDK：11+
> 每个示例类均包含 `main` 方法，可在 IDE 中独立运行。

---

## 示例总览

| 编号 | 文件 | 主题 | 核心知识点 |
|:---:|---|---|---|
| 01 | [Demo01_ThreadPoolExecutor.java](Demo01_ThreadPoolExecutor.java) | 推荐写法 | 手动创建线程池、七大参数、自定义 ThreadFactory、有界队列 |
| 02 | [Demo02_Executors.java](Demo02_Executors.java) | Executors 工厂方法 | Fixed / Cached / Single / Scheduled 及 OOM 风险 |
| 03 | [Demo03_SubmitFlow.java](Demo03_SubmitFlow.java) | 任务提交流程 | 核心线程 → 队列 → 扩容 → 拒绝，含运行时指标打印 |
| 04 | [Demo04_RejectedPolicy.java](Demo04_RejectedPolicy.java) | 四种拒绝策略 | Abort / CallerRuns / Discard / DiscardOldest |
| 05 | [Demo05_ScheduledTask.java](Demo05_ScheduledTask.java) | 定时与周期任务 | schedule / scheduleAtFixedRate / scheduleWithFixedDelay |
| 06 | [Demo06_CallableFuture.java](Demo06_CallableFuture.java) | 有返回值任务 | submit + Future、invokeAll、异常传递、超时与 cancel |
| 07 | [Demo07_MonitorAndShutdown.java](Demo07_MonitorAndShutdown.java) | 监控与优雅关闭 | 关键指标打点、shutdown / awaitTermination / shutdownNow |

---

## 01 推荐写法：手动创建 ThreadPoolExecutor

**文件**：`Demo01_ThreadPoolExecutor.java`

阿里巴巴 Java 开发手册明令禁止使用 `Executors` 创建线程池，推荐手动 `new ThreadPoolExecutor`。

### 七大核心参数

| 参数 | 说明 | 示例值 |
|---|---|---|
| `corePoolSize` | 核心线程数，空闲也不回收（除非 `allowCoreThreadTimeOut=true`） | 2 |
| `maximumPoolSize` | 最大线程数 | 4 |
| `keepAliveTime` | 非核心线程空闲存活时间 | 60s |
| `unit` | 时间单位 | `TimeUnit.SECONDS` |
| `workQueue` | 有界等待队列（必须有界，防止 OOM） | `ArrayBlockingQueue(100)` |
| `threadFactory` | 线程工厂（建议自定义线程名） | Lambda 命名为 `biz-pool-N` |
| `handler` | 拒绝策略 | `CallerRunsPolicy`（背压） |

### 关键代码

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    2, 4, 60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(100),
    r -> new Thread(r, "biz-pool-" + idx.getAndIncrement()),
    new ThreadPoolExecutor.CallerRunsPolicy()
);
pool.allowCoreThreadTimeOut(true);  // 核心线程空闲也回收
```

---

## 02 Executors 工厂方法提供的常见线程池

**文件**：`Demo02_Executors.java`

> **生产环境不推荐**：Fixed/Single 使用无界队列 `LinkedBlockingQueue`，Cached/Scheduled 最大线程数为 `Integer.MAX_VALUE`，都可能导致 OOM。

| 工厂方法 | 核心/最大线程 | 队列 | 适用场景 | 风险 |
|---|---|---|---|---|
| `newFixedThreadPool(n)` | 固定 n | LinkedBlockingQueue（**无界**） | 负载稳定的并发任务 | 队列堆积 OOM |
| `newCachedThreadPool` | 0 / MAX_VALUE | SynchronousQueue | 大量短任务、突发流量 | 线程数无限膨胀 OOM |
| `newSingleThreadExecutor` | 1 / 1 | LinkedBlockingQueue（**无界**） | 需保证顺序执行 | 队列堆积 OOM |
| `newScheduledThreadPool(n)` | n / MAX_VALUE | DelayedWorkQueue | 定时/周期任务 | 线程数膨胀 |

示例中还演示了自定义 `ThreadFactory`，统一命名前缀便于问题定位：

```java
private static ThreadFactory namedFactory(String prefix) {
    AtomicInteger idx = new AtomicInteger(1);
    return r -> new Thread(r, prefix + "-" + idx.getAndIncrement());
}
```

---

## 03 任务提交流程验证

**文件**：`Demo03_SubmitFlow.java`

### 提交流程

```
提交任务
  │
  ▼
核心线程是否已满？ ──否──> 创建核心线程执行
  │是
  ▼
等待队列是否已满？ ──否──> 任务入队列等待
  │是
  ▼
是否达到最大线程数？ ──否──> 创建非核心线程执行
  │是
  ▼
触发拒绝策略
```

### 示例配置与预期

- 配置：核心 2、最大 4、队列容量 2、`AbortPolicy`
- 一次性提交 7 个任务（每个 sleep 1s），预期：

| 任务编号 | 去向 | 说明 |
|:---:|---|---|
| 1、2 | 核心线程执行 | 创建 2 个核心线程 |
| 3、4 | 进入队列等待 | 队列积压 1→2 |
| 5、6 | 扩容非核心线程 | 总线程数 3→4 |
| 7 | 被拒绝 | 抛 `RejectedExecutionException` |

### 实际运行输出（已验证）

```
提交任务1 后 => 总线程数=1, 活跃线程=1, 队列积压=0
提交任务2 后 => 总线程数=2, 活跃线程=2, 队列积压=0
提交任务3 后 => 总线程数=2, 活跃线程=2, 队列积压=1
提交任务4 后 => 总线程数=2, 活跃线程=2, 队列积压=2
提交任务5 后 => 总线程数=3, 活跃线程=3, 队列积压=2
提交任务6 后 => 总线程数=4, 活跃线程=4, 队列积压=2
任务7 被拒绝（线程数与队列均已满）: RejectedExecutionException
```

---

## 04 四种拒绝策略对比

**文件**：`Demo04_RejectedPolicy.java`

统一配置：核心 1、最大 1、队列容量 1，提交 5 个任务（每个耗时 200ms）。

| 策略 | 类名 | 行为 | 适用场景 |
|:---:|---|---|---|
| Abort | `AbortPolicy` | 抛 `RejectedExecutionException` | 默认策略，调用方可捕获后降级/补偿 |
| CallerRuns | `CallerRunsPolicy` | 由提交线程自己执行 | 天然背压，自动降低提交速率 |
| Discard | `DiscardPolicy` | 静默丢弃新任务 | 可容忍丢失的场景（日志/上报） |
| DiscardOldest | `DiscardOldestPolicy` | 丢弃队列最老的任务再重试 | 新任务优先级高于旧任务 |

### 各策略预期行为

- **Abort**：任务 1 执行，任务 2 入队列，任务 3-5 被拒绝抛异常（被拒绝数=3）
- **CallerRuns**：任务 1 线程池执行，任务 2 入队列，任务 3-5 由 main 线程执行（不抛异常）
- **Discard**：任务 1 执行，任务 2 入队列，任务 3-5 静默丢弃（实际完成数=2）
- **DiscardOldest**：任务 1 执行，后续提交时丢弃队列中最老的任务

---

## 05 定时与周期任务

**文件**：`Demo05_ScheduledTask.java`

使用 `ScheduledThreadPoolExecutor`，常用三个方法：

| 方法 | 语义 | 节奏说明 |
|---|---|---|
| `schedule(task, delay, unit)` | 延迟 `delay` 后执行一次 | 仅执行一次 |
| `scheduleAtFixedRate(task, init, period)` | 固定速率 | 以上一次**开始**时间为基准，间隔 `period`；若任务耗时超过 `period`，下一次不会重叠而是顺延 |
| `scheduleWithFixedDelay(task, init, delay)` | 固定延迟 | 以上一次**结束**时间为基准，再等 `delay` 执行下一次 |

### 示例配置

```java
// 立即开始，每 500ms 一次（固定速率）
pool.scheduleAtFixedRate(task, 0, 500, TimeUnit.MILLISECONDS);

// 首次延迟 1s，每次结束后再等 500ms（固定延迟，任务本身耗时 200ms → 实际间隔 700ms）
pool.scheduleWithFixedDelay(task, 1, 500, TimeUnit.MILLISECONDS);
```

运行 3 秒后关闭，可从输出中观察两种模式的节奏差异。

---

## 06 有返回值任务：Callable + Future

**文件**：`Demo06_CallableFuture.java`

| 方法 | 返回值 | 说明 |
|---|---|---|
| `execute(Runnable)` | void | 无返回值 |
| `submit(Callable)` | `Future<T>` | 返回 Future，`get()` 阻塞获取结果 |
| `invokeAll(Collection<Callable>)` | `List<Future<T>>` | 等待全部完成 |

### 四个场景

1. **单个任务**：`submit` + `get(timeout)` 带超时获取结果
2. **批量任务**：`invokeAll` 等待全部完成后遍历 `Future.get()`
3. **异常传递**：任务内抛异常 → `get()` 时包装为 `ExecutionException`，通过 `getCause()` 获取原始异常
4. **超时控制**：`get(1, TimeUnit.SECONDS)` 超时抛 `TimeoutException`，配合 `cancel(true)` 中断任务

```java
try {
    slow.get(1, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    System.out.println("等待超时，取消任务: " + slow.cancel(true));
}
```

---

## 07 监控指标与优雅关闭

**文件**：`Demo07_MonitorAndShutdown.java`

### 监控指标

| 方法 | 含义 | 关注点 |
|---|---|---|
| `getActiveCount()` | 活跃线程数 | 是否持续打满 |
| `getPoolSize()` | 当前总线程数 | 是否达到最大值 |
| `getLargestPoolSize()` | 历史峰值线程数 | 评估配置是否合理 |
| `getTaskCount()` | 已提交任务总数（近似值） | 吞吐量评估 |
| `getCompletedTaskCount()` | 已完成任务数（近似值） | 处理速度评估 |
| `getQueue().size()` | 队列积压任务数 | **持续上涨说明处理能力不足** |

### 优雅关闭流程

```java
pool.shutdown();                                    // 1. 不再接收新任务
if (!pool.awaitTermination(5, TimeUnit.SECONDS)) { // 2. 限时等待已提交任务完成
    pool.shutdownNow();                            // 3. 超时则强制中断，返回未执行的任务列表
}
```

| 方法 | 行为 |
|---|---|
| `shutdown()` | 不再接收新任务，已提交任务继续执行完 |
| `awaitTermination(timeout)` | 阻塞等待终止，超时返回 `false` |
| `shutdownNow()` | 尝试中断正在执行的任务，返回队列中未执行的任务列表 |
| `isShutdown()` | 是否已调用 `shutdown()` |
| `isTerminated()` | 是否已完全终止 |

---

## 线程数配置经验公式

| 任务类型 | 推荐核心线程数 | 说明 |
|---|---|---|
| CPU 密集型 | `CPU 核数 + 1` | 少量线程减少上下文切换 |
| IO 密集型 | `CPU 核数 × 2` | 更多线程弥补 IO 等待 |
| 混合型 | `CPU 核数 × (1 + 等待时间/计算时间)` | 更精确的公式 |

```java
int cpuCores = Runtime.getRuntime().availableProcessors();
```

---

## 生产环境注意事项

1. **队列必须有界**：使用 `ArrayBlockingQueue(capacity)`，容量按峰值和内存评估
2. **自定义 ThreadFactory**：给线程命名，dump 栈时快速定位业务
3. **合理选择拒绝策略**：不可丢的任务用 `CallerRunsPolicy`；可丢弃的用 `DiscardPolicy`；关键任务可自定义策略（持久化到 MQ/DB 后补偿）
4. **线程池务必 shutdown**：应用关闭时调用 `shutdown()` + `awaitTermination()`，或注册为 Spring Bean 随容器销毁
5. **监控打点**：定期上报活跃数、队列积压、拒绝次数，据此动态调参
6. **业务隔离**：不同业务使用独立线程池（舱壁模式），防止慢任务拖垮整个应用
