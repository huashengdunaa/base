package com.demo.threadpool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 03 任务提交流程验证
 *
 * 配置：核心 2、最大 4、队列容量 2、AbortPolicy（抛异常）
 * 流程：核心线程满 -> 任务入队列 -> 队列满 -> 扩容到最大线程数 -> 仍满 -> 触发拒绝策略
 *
 * 预期现象（任务都在 sleep，提交速度远快于执行速度）：
 *   任务1、2：创建 2 个核心线程执行
 *   任务3、4：进入队列等待
 *   任务5、6：队列已满，扩容出 2 个非核心线程执行（总线程数 4）
 *   任务7  ：线程数和队列都满，抛出 RejectedExecutionException
 */
public class Demo03_SubmitFlow {

    public static void main(String[] args) {
        AtomicInteger idx = new AtomicInteger(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),
                r -> new Thread(r, "flow-pool-" + idx.getAndIncrement()),
                new ThreadPoolExecutor.AbortPolicy()
        );

        for (int i = 1; i <= 7; i++) {
            int taskId = i;
            try {
                pool.execute(() -> {
                    System.out.println("任务" + taskId + " 开始执行, 线程=" + Thread.currentThread().getName());
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                System.out.printf("提交任务%d 后 => 总线程数=%d, 活跃线程=%d, 队列积压=%d%n",
                        taskId, pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size());
            } catch (RejectedExecutionException e) {
                System.out.println("任务" + taskId + " 被拒绝（线程数与队列均已满）: " + e.getClass().getSimpleName());
            }
        }

        pool.shutdown();
    }
}
