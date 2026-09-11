package com.demo.threadpool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 06 提交有返回值的任务：Callable + Future
 *
 * execute(Runnable)：无返回值
 * submit(Callable) ：返回 Future，可通过 get() 获取结果 / 取消任务
 */
public class Demo06_CallableFuture {

    public static void main(String[] args) throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> new Thread(r, "callable-pool"));

        // 1. 单个任务：get() 阻塞等待结果，建议带超时
        Future<String> future = pool.submit(() -> {
            TimeUnit.MILLISECONDS.sleep(300);
            return "hello";
        });
        String result = future.get(2, TimeUnit.SECONDS);
        System.out.println("单个任务返回: " + result);

        // 2. 批量任务：invokeAll 等待全部完成
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            int taskId = i;
            tasks.add(() -> {
                TimeUnit.MILLISECONDS.sleep(200);
                return "任务" + taskId + "结果";
            });
        }
        List<Future<String>> futures = pool.invokeAll(tasks);
        for (Future<String> f : futures) {
            System.out.println("批量任务返回: " + f.get());
        }

        // 3. 任务内部抛异常：get() 时包装为 ExecutionException 抛出
        Future<?> badFuture = pool.submit(() -> {
            throw new IllegalStateException("任务内部出错了");
        });
        try {
            badFuture.get();
        } catch (ExecutionException e) {
            System.out.println("捕获到任务异常: " + e.getCause());
        }

        // 4. 超时控制：防止 get() 永久阻塞
        Future<String> slow = pool.submit(() -> {
            TimeUnit.SECONDS.sleep(5);
            return "不会等到的结果";
        });
        try {
            slow.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("等待超时，取消任务: " + slow.cancel(true));
        }

        pool.shutdown();
    }
}
