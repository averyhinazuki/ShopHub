package com.example.shophub.service;

import com.example.shophub.entity.Product;
import com.example.shophub.entity.ProductInventory;
import com.example.shophub.enums.ProductStatus;
import com.example.shophub.repository.jpa.ProductInventoryRepository;
import com.example.shophub.repository.jpa.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock ProductRepository productRepository;
    @Mock ProductInventoryRepository inventoryRepository;
    @Mock CategoryService categoryService;
    @Mock ProductCacheService cacheService;
    @Mock RedissonClient redissonClient;

    @InjectMocks ProductService productService;

    @Test
    void listProducts_doesNotQueryInventorySeparately() {
        // Simulate what @EntityGraph does: inventory is already populated on the Product
        Product product = new Product();
        product.setId(1L);
        product.setName("Widget");
        product.setPrice(BigDecimal.TEN);
        product.setStatus(ProductStatus.ACTIVE);

        ProductInventory inv = new ProductInventory();
        inv.setAvailableStock(50);
        inv.setTotalStock(100);
        product.setInventory(inv);

        when(productRepository.findByStatus(eq(ProductStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of(product)));

        productService.listProducts(null, null, PageRequest.of(0, 20));

        // EntityGraph pre-loads inventory — inventoryRepository must not be called
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void listProductsByCategory_doesNotQueryInventorySeparately() {
        Product product = new Product();
        product.setId(2L);
        product.setName("Gadget");
        product.setPrice(BigDecimal.ONE);
        product.setStatus(ProductStatus.ACTIVE);

        ProductInventory inv = new ProductInventory();
        inv.setAvailableStock(10);
        inv.setTotalStock(10);
        product.setInventory(inv);

        when(productRepository.findByStatusAndCategoryId(eq(ProductStatus.ACTIVE), eq(5L), any()))
                .thenReturn(new PageImpl<>(List.of(product)));

        productService.listProducts(5L, null, PageRequest.of(0, 20));

        verifyNoInteractions(inventoryRepository);
    }
}
