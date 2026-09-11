package com.demo;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    public static void main(String[] args) {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(5,
                10,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "MyThread-" + (new AtomicInteger(1).incrementAndGet()));
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        for (int i = 1; i <= 5; i++) {
        threadPoolExecutor.execute(
                () -> System.out.println("execute"));
        }
        threadPoolExecutor.shutdown();
        try {
            if (threadPoolExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                System.out.println("线程池已关闭");
            }
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}