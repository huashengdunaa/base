package com.demo.inventory;

import com.demo.inventory.entity.Product;
import com.demo.inventory.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化商品：id=1, name=秒杀商品, stock=10, version=0
 * 用 CommandLineRunner 保证在容器和 JPA 都就绪后执行。
 */
@Component
public class InventoryDataInitializer implements CommandLineRunner {

    @Autowired
    private ProductRepository productRepository;

    @Override
    public void run(String... args) {
        productRepository.save(new Product(1L, "秒杀商品", 10, 0));
        System.out.println("[Inventory] 初始化商品：id=1, stock=10, version=0");
    }
}
