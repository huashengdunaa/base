package com.demo.threadpool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 07 线程池监控指标与优雅关闭
 *
 * 监控指标（建议定期打点上报）：
 *   getActiveCount()         活跃线程数
 *   getPoolSize()            当前总线程数
 *   getLargestPoolSize()     历史峰值线程数
 *   getTaskCount()           已提交任务总数（近似值）
 *   getCompletedTaskCount()  已完成任务数（近似值）
 *   getQueue().size()        队列积压任务数（持续上涨说明处理能力不足）
 *
 * 优雅关闭：
 *   shutdown()           不再接收新任务，已提交任务继续执行完
 *   awaitTermination()   阻塞等待线程池终止，超时返回 false
 *   shutdownNow()        尝试中断正在执行的任务，并返回队列中未执行的任务
 */
public class Demo07_MonitorAndShutdown {

    public static void main(String[] args) throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> new Thread(r, "monitor-pool"));

        for (int i = 1; i <= 6; i++) {
            int taskId = i;
            pool.execute(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("任务" + taskId + " 完成");
            });
        }

        // 打印一次运行中的监控快照
        printStats("运行中", pool);

        // 优雅关闭：先 shutdown，再限时等待；超时则强制 shutdownNow
        pool.shutdown();
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            System.out.println("等待超时，强制关闭，未开始执行的任务数=" + pool.shutdownNow().size());
        }

        printStats("关闭后", pool);
        System.out.println("isShutdown=" + pool.isShutdown() + ", isTerminated=" + pool.isTerminated());
    }

    private static void printStats(String phase, ThreadPoolExecutor pool) {
        System.out.printf("[%s] 总线程=%d, 活跃=%d, 峰值=%d, 已提交=%d, 已完成=%d, 队列积压=%d%n",
                phase,
                pool.getPoolSize(),
                pool.getActiveCount(),
                pool.getLargestPoolSize(),
                pool.getTaskCount(),
                pool.getCompletedTaskCount(),
                pool.getQueue().size());
    }
}
