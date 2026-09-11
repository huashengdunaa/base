package com.demo.inventory.repository;

import com.demo.inventory.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.util.Optional;

/**
 * 商品仓储。
 *
 * 三种扣减方案对应的关键方法：
 *   1. 无锁：findById（默认）
 *   2. 悲观锁：findByIdForUpdate —— SELECT ... FOR UPDATE，对查询到的行加 X 锁
 *   3. 乐观锁：decrementStockCas —— 用 version 做 CAS 的 UPDATE
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 悲观锁查询：SELECT * FROM product WHERE id=? FOR UPDATE
     * LockModeType.PESSIMISTIC_WRITE 对应数据库的 X 锁，
     * 同一事务内查到行后，其他事务再执行同一条 FOR UPDATE 会被阻塞，
     * 直到持有锁的事务提交/回滚。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /**
     * 乐观锁 CAS：UPDATE product SET stock = stock - 1, version = version + 1
     *              WHERE id = ? AND version = ? AND stock > 0
     *
     * 返回受影响行数：
     *   1  → CAS 成功，扣减生效
     *   0  → 版本不匹配（已被其他线程扣减）或库存已为 0，视为抢购失败
     */
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - 1, p.version = p.version + 1 " +
           "WHERE p.id = :id AND p.version = :expectedVersion AND p.stock > 0")
    int decrementStockCas(@Param("id") Long id,
                           @Param("expectedVersion") Integer expectedVersion);
}
