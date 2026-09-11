package com.demo.threadpool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 01 推荐写法：手动 new ThreadPoolExecutor（阿里巴巴规约推荐，禁止使用 Executors）
 *
 * 七大参数：
 *   corePoolSize     核心线程数，空闲也不回收（除非 allowCoreThreadTimeOut=true）
 *   maximumPoolSize  最大线程数
 *   keepAliveTime    非核心线程空闲存活时间
 *   unit             时间单位
 *   workQueue        有界等待队列（必须有界，防止任务堆积 OOM）
 *   threadFactory    线程工厂（建议自定义线程名，便于 dump 排查）
 *   handler          拒绝策略（队列满且达到最大线程数时触发）
 */
public class Demo01_ThreadPoolExecutor {

    public static void main(String[] args) {
        AtomicInteger threadIdx = new AtomicInteger(1);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,                                      // corePoolSize
                4,                                      // maximumPoolSize
                60L, TimeUnit.SECONDS,                  // keepAliveTime
                new ArrayBlockingQueue<>(100),          // 有界队列
                r -> new Thread(r, "biz-pool-" + threadIdx.getAndIncrement()), // 自定义线程名
                new ThreadPoolExecutor.CallerRunsPolicy()                       // 拒绝策略：由调用线程自己执行（背压）
        );

        // 允许核心线程在空闲 60s 后也回收（默认 false，按需开启）
        pool.allowCoreThreadTimeOut(true);

        for (int i = 1; i <= 8; i++) {
            int taskId = i;
            pool.execute(() -> {
                System.out.println("任务" + taskId + " 由 " + Thread.currentThread().getName() + " 执行");
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 优雅关闭：不再接收新任务，已提交任务执行完
        pool.shutdown();
    }
}
