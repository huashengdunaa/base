package com.demo.inventory.controller;

import com.demo.inventory.entity.Product;
import com.demo.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 库存扣减接口：包含三种方案对比 + 服务端压测。
 *
 * /inventory/deduct/no-lock       无锁，预期超卖
 * /inventory/deduct/pessimistic   悲观锁，预期不超卖
 * /inventory/deduct/optimistic    乐观锁，预期不超卖
 * /inventory/stress-test?strategy=xxx  服务端用 100 线程同时抢 10 件
 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    /** 压测参数 */
    private static final long PRODUCT_ID = 1L;
    private static final int  THREADS    = 100;
    private static final int  STOCK      = 10;

    @Autowired
    private InventoryService inventoryService;

    // ====== 单次扣减接口（方便外部 JMeter/curl 压测调用） ======

    @PostMapping("/deduct/no-lock")
    public Map<String, Object> deductNoLock() {
        return doDeduct(id -> inventoryService.deductNoLock(id), "no-lock");
    }

    @PostMapping("/deduct/pessimistic")
    public Map<String, Object> deductPessimistic() {
        return doDeduct(id -> inventoryService.deductPessimistic(id), "pessimistic");
    }

    @PostMapping("/deduct/optimistic")
    public Map<String, Object> deductOptimistic() {
        return doDeduct(id -> inventoryService.deductOptimistic(id), "optimistic");
    }

    /** 公共的扣减执行逻辑 */
    private Map<String, Object> doDeduct(java.util.function.Function<Long, Boolean> fn, String strategy) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        boolean ok = fn.apply(PRODUCT_ID);
        result.put("strategy", strategy);
        result.put("success", ok);
        result.put("costMs", System.currentTimeMillis() - start);
        return result;
    }

    /** 重置库存为 10（压测前调用） */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        inventoryService.resetStock(PRODUCT_ID, STOCK);
        return viewProduct("已重置");
    }

    /** 查询当前商品状态 */
    @GetMapping("/product")
    public Map<String, Object> viewProduct() {
        return viewProduct(null);
    }

    private Map<String, Object> viewProduct(String msg) {
        Product p = inventoryService.getProduct(PRODUCT_ID);
        Map<String, Object> result = new LinkedHashMap<>();
        if (msg != null) result.put("message", msg);
        result.put("id", p.getId());
        result.put("name", p.getName());
        result.put("stock", p.getStock());
        result.put("version", p.getVersion());
        return result;
    }

    // ====== 服务端内置压测：100 线程同时抢 10 件 ======

    @PostMapping("/stress-test")
    public Map<String, Object> stressTest(@RequestParam(defaultValue = "no-lock") String strategy) {
        // 1. 重置库存
        inventoryService.resetStock(PRODUCT_ID, STOCK);

        // 2. 100 个线程，CountDownLatch 让它们同时发起
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        java.util.function.Function<Long, Boolean> fn;
        switch (strategy) {
            case "pessimistic": fn = id -> inventoryService.deductPessimistic(id); break;
            case "optimistic":  fn = id -> inventoryService.deductOptimistic(id);  break;
            default:            fn = id -> inventoryService.deductNoLock(id);      break;
        }

        long start = System.currentTimeMillis();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();  // 等发令枪
                    boolean ok = fn.apply(PRODUCT_ID);
                    if (ok) success.incrementAndGet(); else fail.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();   // 发令：100 线程同时开抢
        try { done.await(); } catch (InterruptedException ignored) {}
        pool.shutdown();
        long cost = System.currentTimeMillis() - start;

        // 3. 查询最终库存，判断是否超卖
        Product p = inventoryService.getProduct(PRODUCT_ID);
        boolean oversold = p.getStock() < 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy);
        result.put("threads", THREADS);
        result.put("initialStock", STOCK);
        result.put("successCount", success.get());
        result.put("failCount", fail.get());
        result.put("finalStock", p.getStock());
        result.put("version", p.getVersion());
        result.put("oversold", oversold);
        result.put("totalCostMs", cost);
        log.info("[压测] {} 线程={} 初始库存={} 成功={} 失败={} 最终库存={} 超卖={} 耗时={}ms",
                strategy, THREADS, STOCK, success.get(), fail.get(), p.getStock(), oversold, cost);
        return result;
    }
}
