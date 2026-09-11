package com.demo.threadpool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 04 四种拒绝策略对比
 *
 * 统一配置：核心 1、最大 1、队列容量 1，一次性提交 5 个任务（每个耗时 200ms）
 *   - AbortPolicy：       默认策略，直接抛 RejectedExecutionException
 *   - CallerRunsPolicy：  由提交任务的线程（main）自己执行，形成天然背压
 *   - DiscardPolicy：     静默丢弃新任务
 *   - DiscardOldestPolicy：丢弃队列中最老的任务，再尝试提交
 */
public class Demo04_RejectedPolicy {

    public static void main(String[] args) throws InterruptedException {
        testAbortPolicy();
        testCallerRunsPolicy();
        testDiscardPolicy();
        testDiscardOldestPolicy();
    }

    /** 1. AbortPolicy：抛出异常，调用方可捕获后做降级/补偿 */
    private static void testAbortPolicy() throws InterruptedException {
        System.out.println("---- AbortPolicy ----");
        ThreadPoolExecutor pool = newPool("abort", new ThreadPoolExecutor.AbortPolicy());
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 1; i <= 5; i++) {
            int taskId = i;
            try {
                pool.execute(() -> runTask("Abort", taskId));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                rejected.incrementAndGet();
                System.out.println("任务" + taskId + " 被拒绝并抛异常");
            }
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("被拒绝任务数=" + rejected.get());
    }

    /** 2. CallerRunsPolicy：提交线程（main）亲自执行，提交速度被自动降下来 */
    private static void testCallerRunsPolicy() throws InterruptedException {
        System.out.println("---- CallerRunsPolicy ----");
        ThreadPoolExecutor pool = newPool("caller", new ThreadPoolExecutor.CallerRunsPolicy());
        for (int i = 1; i <= 5; i++) {
            int taskId = i;
            pool.execute(() -> runTask("CallerRuns", taskId));
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /** 3. DiscardPolicy：新任务被静默丢弃，不抛异常 */
    private static void testDiscardPolicy() throws InterruptedException {
        System.out.println("---- DiscardPolicy ----");
        AtomicInteger done = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                r -> new Thread(r, "discard-pool"),
                new ThreadPoolExecutor.DiscardPolicy());
        for (int i = 1; i <= 5; i++) {
            int taskId = i;
            pool.execute(() -> {
                runTask("Discard", taskId);
                done.incrementAndGet();
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("实际完成任务数=" + done.get() + "（其余被静默丢弃）");
    }

    /** 4. DiscardOldestPolicy：丢弃队列里排队最久的任务 */
    private static void testDiscardOldestPolicy() throws InterruptedException {
        System.out.println("---- DiscardOldestPolicy ----");
        ThreadPoolExecutor pool = newPool("discard-oldest", new ThreadPoolExecutor.DiscardOldestPolicy());
        for (int i = 1; i <= 5; i++) {
            int taskId = i;
            pool.execute(() -> runTask("DiscardOldest", taskId));
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static ThreadPoolExecutor newPool(String name, RejectedExecutionHandler handler) {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                r -> new Thread(r, name + "-pool"),
                handler);
    }

    private static void runTask(String policy, int taskId) {
        System.out.println(policy + " 任务" + taskId + " 由 " + Thread.currentThread().getName() + " 执行");
        try {
            TimeUnit.MILLISECONDS.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
