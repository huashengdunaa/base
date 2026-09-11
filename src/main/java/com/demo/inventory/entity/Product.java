package com.demo.inventory.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 商品实体（库存扣减 Demo）。
 *
 * 字段说明：
 *   id        商品 ID（手动赋值，避免自增干扰压测控制）
 *   name      商品名
 *   stock     库存数量
 *   version   乐观锁版本号：每次扣减 +1，CAS 更新时比对
 *
 * 注意：这里没有使用 JPA 的 @Version 自动乐观锁，
 * 而是手动写 CAS SQL，便于在 Service 层看清「版本不匹配 → 重试/失败」的过程。
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    private Long id;

    private String name;

    @Column(nullable = false)
    private Integer stock;

    /** 乐观锁版本号；@Version 仅用于演示命名，实际 CAS 由 SQL 完成 */
    @Column(nullable = false)
    private Integer version;

    public Product() {}

    public Product(Long id, String name, Integer stock, Integer version) {
        this.id = id;
        this.name = name;
        this.stock = stock;
        this.version = version;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
