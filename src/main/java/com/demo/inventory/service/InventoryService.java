package com.demo.inventory.service;

import com.demo.inventory.entity.Product;
import com.demo.inventory.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 库存扣减 Service：三种方案对比。
 *
 * 事务隔离级别由 Spring 默认的 REQUIRED 传播 + 数据库默认隔离级别决定
 * （H2 默认 READ_COMMITTED）。悲观锁的行锁在该隔离级别下即可生效，
 * 不需要 REPEATABLE_READ/SERIALIZABLE。
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    @Autowired
    private ProductRepository productRepository;

    /**
     * 方案1：不加锁。
     *
     * 典型「读-改-写」非原子操作，并发下大量线程读到同样的 stock=10，
     * 各自 -1 后写回，最终库存被多扣 → 超卖。
     */
    @Transactional
    public boolean deductNoLock(Long productId) {
        Product p = productRepository.findById(productId).orElseThrow();
        if (p.getStock() <= 0) {
            return false;  // 库存不足
        }
        // 这里模拟一个微小延迟，让超卖现象更明显
        sleep(5);
        p.setStock(p.getStock() - 1);
        productRepository.save(p);
        return true;
    }

    /**
     * 方案2：悲观锁。
     *
     * 查询时直接 SELECT ... FOR UPDATE，数据库对该行加 X 锁。
     * 其他事务执行同一条 FOR UPDATE 会被阻塞排队，
     * 直到当前事务提交/回滚才拿到锁，因此同一时刻只有一个事务能改库存 → 不超卖。
     *
     * 注意：FOR UPDATE 必须在事务内有效；事务结束时锁才释放。
     */
    @Transactional
    public boolean deductPessimistic(Long productId) {
        Product p = productRepository.findByIdForUpdate(productId).orElseThrow();
        if (p.getStock() <= 0) {
            return false;
        }
        // 即使加延迟，其他事务也只能排队，不会读到中间状态
        sleep(5);
        p.setStock(p.getStock() - 1);
        productRepository.save(p);
        return true;
    }

    /**
     * 方案3：乐观锁（CAS）。
     *
     * 读时不加锁，扣减时用 UPDATE ... WHERE version = ? 做 CAS：
     *   - 若版本号匹配：扣减成功，version + 1
     *   - 若版本号不匹配：说明别人已经改过，本次扣减失败
     *
     * 这里实现为「失败即放弃」，不重试（单次抢购场景常见做法）。
     * 实际生产可加 N 次自旋重试。
     */
    @Transactional
    public boolean deductOptimistic(Long productId) {
        Product p = productRepository.findById(productId).orElseThrow();
        if (p.getStock() <= 0) {
            return false;
        }
        int affected = productRepository.decrementStockCas(productId, p.getVersion());
        return affected > 0;
    }

    /**
     * 重置库存到指定值，version 清零。压测前调用，保证对比基线一致。
     */
    @Transactional
    public void resetStock(Long productId, int stock) {
        Optional<Product> opt = productRepository.findById(productId);
        Product p = opt.orElseGet(() -> new Product(productId, "秒杀商品", stock, 0));
        p.setStock(stock);
        p.setVersion(0);
        productRepository.save(p);
        log.info("重置商品 {} 库存为 {}", productId, stock);
    }

    public Product getProduct(Long productId) {
        return productRepository.findById(productId).orElse(null);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
