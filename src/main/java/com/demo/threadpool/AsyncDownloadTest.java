package com.demo.threadpool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池批量下载任务（CompletableFuture.orTimeout 方案）
 *   - 模拟 100 个下载任务（每个 sleep 随机 1-3 秒）
 *   - ThreadPoolExecutor：核心 2、最大 4、队列 100
 *   - 单任务独立 5 秒超时，超时/异常标记为失败
 *   - allOf 等待全部落定后统计总耗时、成功数、失败数
 */
public class AsyncDownloadTest {
    public static void main(String[] args) {
        AtomicInteger threadIdx = new AtomicInteger(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,                                          // corePoolSize
                4,                                          // maximumPoolSize
                30L, TimeUnit.SECONDS,                      // keepAliveTime
                new ArrayBlockingQueue<>(100),              // workQueue（有界，容量 100）
                r -> new Thread(r, "download-pool-" + threadIdx.getAndIncrement()),
                new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由调用线程执行（背压）
        );

        int taskCount = 100;
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(taskCount);

        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= taskCount; i++) {
            int taskId = i;
            futures.add(CompletableFuture
                    .supplyAsync(() -> download(taskId), pool)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(e -> {
                        Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                        System.out.println("任务" + taskId + " 失败: " + cause);
                        return Boolean.FALSE;
                    }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();

        for (CompletableFuture<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.join())) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }
        }

        pool.shutdown();
        long totalCost = System.currentTimeMillis() - startTime;

        System.out.println("==================== 统计 ====================");
        System.out.println("总任务数: " + taskCount);
        System.out.println("成功数:   " + successCount.get());
        System.out.println("失败数:   " + failureCount.get());
        System.out.println("总耗时:   " + totalCost + "ms");
    }

    private static boolean download(int taskId) {
        long taskStart = System.currentTimeMillis();
        try {
            long sleepMs = 1000L + (long) (Math.random() * 2000);
            TimeUnit.MILLISECONDS.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        System.out.println("任务" + taskId + " 完成, 耗时"
                + (System.currentTimeMillis() - taskStart) + "ms, 线程="
                + Thread.currentThread().getName());
        return true;
    }
}