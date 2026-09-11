package com.demo.threadpool;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 05 ScheduledThreadPoolExecutor 定时 / 周期任务
 *
 * 常用方法：
 *   schedule(Runnable, delay, unit)           延迟 delay 后执行一次
 *   scheduleAtFixedRate(task, initial, period) 固定速率：每隔 period 执行一次
 *                                              （以上一次【开始】时间为基准，任务耗时超过 period 会顺延不重叠）
 *   scheduleWithFixedDelay(task, initial, delay) 固定延迟：上一次【结束】后再等 delay 执行下一次
 */
public class Demo05_ScheduledTask {

    public static void main(String[] args) throws InterruptedException {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);

        // 1. 延迟 1 秒执行一次
        pool.schedule(
                () -> System.out.println("[schedule] 延迟 1s 的一次性任务"),
                1, TimeUnit.SECONDS);

        // 2. 固定速率：立即开始，每 500ms 一次
        AtomicInteger fixedCount = new AtomicInteger();
        pool.scheduleAtFixedRate(
                () -> System.out.println("[fixedRate] 第 " + fixedCount.incrementAndGet() + " 次, "
                        + System.currentTimeMillis() % 100_000),
                0, 500, TimeUnit.MILLISECONDS);

        // 3. 固定延迟：首次延迟 1s，每次结束后再等 500ms
        AtomicInteger delayCount = new AtomicInteger();
        pool.scheduleWithFixedDelay(
                () -> {
                    System.out.println("[fixedDelay] 第 " + delayCount.incrementAndGet() + " 次");
                    try {
                        TimeUnit.MILLISECONDS.sleep(200); // 模拟任务耗时
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                1, 500, TimeUnit.MILLISECONDS);

        // 运行 3 秒后关闭，观察输出节奏差异
        TimeUnit.SECONDS.sleep(3);
        pool.shutdown();
        System.out.println("定时线程池已关闭");
    }
}
