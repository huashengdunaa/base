package com.demo.threadpool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 02 Executors 工厂方法提供的常见线程池
 *
 * 注意：以下写法【生产环境不推荐】（阿里巴巴 Java 开发手册明令禁止），
 * 因为 Fixed/Single 使用无界队列 LinkedBlockingQueue，Cached/Scheduled
 * 最大线程数为 Integer.MAX_VALUE，都可能导致 OOM。了解其特性即可，
 * 生产环境请参考 Demo01 手动 new ThreadPoolExecutor。
 */
public class Demo02_Executors {

    public static void main(String[] args) throws InterruptedException {
        // 1. newFixedThreadPool：固定线程数，适用于负载稳定的并发任务
        ExecutorService fixedPool = Executors.newFixedThreadPool(2, namedFactory("fixed"));

        // 2. newCachedThreadPool：核心 0、最大 Integer.MAX_VALUE，线程空闲 60s 回收
        //    适用于大量短任务、突发流量；线程数可能无限膨胀
        ExecutorService cachedPool = Executors.newCachedThreadPool(namedFactory("cached"));

        // 3. newSingleThreadExecutor：单线程，保证任务按提交顺序串行执行
        ExecutorService singlePool = Executors.newSingleThreadExecutor(namedFactory("single"));

        // 4. newScheduledThreadPool：支持定时/周期执行，详见 Demo05
        ScheduledExecutorService scheduledPool = Executors.newScheduledThreadPool(2, namedFactory("scheduled"));

        for (int i = 1; i <= 3; i++) {
            int taskId = i;
            fixedPool.execute(() -> System.out.println("fixedPool 执行任务" + taskId));
            cachedPool.execute(() -> System.out.println("cachedPool 执行任务" + taskId));
            singlePool.execute(() -> System.out.println("singlePool 执行任务" + taskId));
        }

        // 延迟 1 秒执行一次
        scheduledPool.schedule(
                () -> System.out.println("scheduledPool 延迟任务执行"),
                1, TimeUnit.SECONDS);

        fixedPool.shutdown();
        cachedPool.shutdown();
        singlePool.shutdown();
        scheduledPool.shutdown();
        scheduledPool.awaitTermination(2, TimeUnit.SECONDS);
    }

    /** 自定义线程工厂：给线程统一命名前缀，便于问题定位 */
    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger idx = new AtomicInteger(1);
        return r -> new Thread(r, prefix + "-" + idx.getAndIncrement());
    }
}
