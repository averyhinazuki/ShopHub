package com.example.shophub.service;

import com.example.shophub.dto.product.*;
import com.example.shophub.entity.Category;
import com.example.shophub.entity.Product;
import com.example.shophub.entity.ProductInventory;
import com.example.shophub.enums.ProductStatus;
import com.example.shophub.exception.ResourceNotFoundException;
import com.example.shophub.repository.jpa.ProductInventoryRepository;
import com.example.shophub.repository.jpa.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository          productRepository;
    private final ProductInventoryRepository inventoryRepository;
    private final CategoryService            categoryService;
    private final ProductCacheService        cacheService;
    private final RedissonClient             redissonClient;

    // ── Reads (public) ──────────────────────────────────────────────────────

    /**
     * Paginated listing of ACTIVE products, optionally filtered by category id or
     * name fragment. Not cached — caching applies to single-product detail only.
     */
    public Page<ProductResponse> listProducts(Long categoryId, String search, Pageable pageable) {
        if (categoryId != null) {
            return productRepository
                    .findByStatusAndCategoryId(ProductStatus.ACTIVE, categoryId, pageable)
                    .map(this::toResponseWithStock);
        }
        if (search != null && !search.isBlank()) {
            return productRepository
                    .findByStatusAndNameContainingIgnoreCase(ProductStatus.ACTIVE, search, pageable)
                    .map(this::toResponseWithStock);
        }
        return productRepository
                .findByStatus(ProductStatus.ACTIVE, pageable)
                .map(this::toResponseWithStock);
    }

    /**
     * Single product detail with live stock. Cache-aside: return the Redis value
     * on a hit, otherwise read MySQL, populate Redis (TTL 60s), and return.
     */
    public ProductResponse getProduct(Long id) {
        ProductResponse cached = cacheService.getDetail(id);
        if (cached != null) {
            log.debug("[Cache] HIT product:{}", id);
            return cached;
        }
        log.debug("[Cache] MISS product:{}", id);
        Product product = findActiveOrThrow(id);
        ProductInventory inv = inventoryRepository.findByProductId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for product: " + id));
        ProductResponse response = toResponse(product, inv);
        cacheService.setDetail(id, response);
        return response;
    }

    // ── Admin writes ────────────────────────────────────────────────────────

    /** Creates the product and its inventory row in one transaction — a product
     *  never exists without an inventory record. */
    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        Category category = categoryService.findEntityById(request.getCategoryId());

        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setCategory(category);
        product.setImageUrl(request.getImageUrl());
        product.setStatus(ProductStatus.ACTIVE);
        productRepository.save(product);

        ProductInventory inv = new ProductInventory();
        inv.setProduct(product);
        inv.setTotalStock(request.getInitialStock());
        inv.setAvailableStock(request.getInitialStock());
        inventoryRepository.save(inv);

        log.info("[Product] Created product:{} '{}' with initialStock={}", product.getId(), product.getName(), request.getInitialStock());
        return toResponse(product, inv);
    }

    /**
     * Updates catalog fields only (name, price, description, imageUrl, status).
     * status=INACTIVE soft-deletes: hidden from listings but retained in
     * order_items history. Invalidates the detail cache; :stock is untouched.
     */
    @Transactional
    public ProductResponse updateProduct(Long id, UpdateProductRequest request) {
        Product product = findActiveOrThrow(id);

        if (request.getName() != null)        product.setName(request.getName());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getPrice() != null)       product.setPrice(request.getPrice());
        if (request.getImageUrl() != null)    product.setImageUrl(request.getImageUrl());
        if (request.getStatus() != null)      product.setStatus(request.getStatus());
        if (request.getCategoryId() != null) {
            product.setCategory(categoryService.findEntityById(request.getCategoryId()));
        }
        productRepository.save(product);

        cacheService.deleteCache(id);
        cacheService.scheduleSecondDeletion(id);

        ProductInventory inv = inventoryRepository.findByProductId(id).orElseThrow();
        return toResponse(product, inv);
    }

    /**
     * Admin inventory adjustment. delta > 0 restocks (total + available); delta < 0
     * is a correction against available only. Runs under lock:product:{id} — the
     * same lock as checkout — with delayed double cache deletion around the write.
     */
    @Transactional
    public void adjustInventory(Long productId, InventoryAdjustRequest request) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found: " + productId);
        }
        int delta = request.getDelta();

        RLock lock = redissonClient.getLock("lock:product:" + productId);
        try {
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!acquired) throw new RuntimeException("Could not acquire lock for product: " + productId);

            // Guard inside the lock so it reads committed state.
            if (delta < 0) {
                ProductInventory inv = inventoryRepository.findByProductId(productId).orElseThrow();
                if (inv.getAvailableStock() + delta < 0) {
                    throw new IllegalArgumentException(
                            "Adjustment would make availableStock negative (current="
                            + inv.getAvailableStock() + ", delta=" + delta + ")");
                }
            }

            cacheService.deleteCache(productId); // first deletion, before the write
            inventoryRepository.adjustStock(productId, delta);
            log.info("[Inventory] product:{} adjusted by delta={} reason={}", productId, delta, request.getReason());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock interrupted for product: " + productId);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }

        // Second deletion runs ~500ms later on a background thread.
        cacheService.scheduleSecondDeletion(productId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Product findActiveOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    /** For list queries — inventory is already joined via @EntityGraph. */
    private ProductResponse toResponseWithStock(Product product) {
        return toResponse(product, product.getInventory());
    }

    ProductResponse toResponse(Product product, ProductInventory inv) {
        ProductResponse res = new ProductResponse();
        res.setId(product.getId());
        res.setName(product.getName());
        res.setDescription(product.getDescription());
        res.setPrice(product.getPrice());
        res.setImageUrl(product.getImageUrl());
        res.setStatus(product.getStatus());
        res.setCreatedAt(product.getCreatedAt());
        if (product.getCategory() != null) {
            res.setCategoryId(product.getCategory().getId());
            res.setCategoryName(product.getCategory().getName());
        }
        if (inv != null) {
            res.setAvailableStock(inv.getAvailableStock());
            res.setTotalStock(inv.getTotalStock());
        }
        return res;
    }
}
