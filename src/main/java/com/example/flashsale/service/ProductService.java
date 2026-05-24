package com.example.flashsale.service;

import com.example.flashsale.dto.product.*;
import com.example.flashsale.entity.Category;
import com.example.flashsale.entity.Product;
import com.example.flashsale.entity.ProductInventory;
import com.example.flashsale.enums.ProductStatus;
import com.example.flashsale.exception.ResourceNotFoundException;
import com.example.flashsale.repository.jpa.ProductInventoryRepository;
import com.example.flashsale.repository.jpa.ProductRepository;
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
     * Paginated product listing — only ACTIVE products.
     * Filters: ?category={id}  or  ?search={name fragment}
     * Cache not applied on list queries (only on single detail).
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
     * Single product detail + live stock.
     *
     * Cache-aside read:
     *   1. Check product:{id}:detail in Redis
     *   2. HIT  → return cached value
     *   3. MISS → read MySQL → populate Redis (TTL 60s) → return
     */
    public ProductResponse getProduct(Long id) {
        // 1. Cache hit?
        ProductResponse cached = cacheService.getDetail(id);
        if (cached != null) {
            log.debug("[Cache] HIT product:{}", id);
            return cached;
        }
        // 2. Cache miss → MySQL
        log.debug("[Cache] MISS product:{} — loading from MySQL", id);
        Product product = findActiveOrThrow(id);
        ProductInventory inv = inventoryRepository.findByProductId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory not found for product: " + id));
        ProductResponse response = toResponse(product, inv);
        // 3. Populate cache
        cacheService.setDetail(id, response);
        return response;
    }

    // ── Admin writes ────────────────────────────────────────────────────────

    /**
     * Creates product + inventory row in a single transaction.
     * A product never exists without an inventory record.
     */
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

        // Inventory row created in same tx — product never exists without inventory
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
     * status=INACTIVE soft-deletes: product disappears from listings but
     * stays in order_items history.
     * Invalidates product:{id}:detail cache (no stock change so :stock stays valid).
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

        // Catalog-only update: only detail cache needs invalidation, not :stock
        cacheService.deleteCache(id);
        cacheService.scheduleSecondDeletion(id);

        ProductInventory inv = inventoryRepository.findByProductId(id).orElseThrow();
        return toResponse(product, inv);
    }

    /**
     * Admin inventory adjustment — the full cache-aside write path (Section 6).
     *
     * delta > 0 → restock  (totalStock + delta, availableStock + delta)
     * delta < 0 → correction / damaged  (availableStock + delta only)
     *
     * Write path:
     *   1. Acquire lock:product:{id}   (same lock as checkout — serializes all writers)
     *   2. Delete product:{id}:detail AND product:{id}:stock  ← first deletion
     *   3. UPDATE product_inventory (MySQL)
     *   4. Release lock
     *   5. Schedule async second deletion ~500ms later
     */
    @Transactional
    public void adjustInventory(Long productId, InventoryAdjustRequest request) {
        // Validate product exists
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found: " + productId);
        }
        int delta = request.getDelta();

        // Guard: available stock cannot go negative
        if (delta < 0) {
            ProductInventory inv = inventoryRepository.findByProductId(productId).orElseThrow();
            if (inv.getAvailableStock() + delta < 0) {
                throw new IllegalArgumentException(
                        "Adjustment would make availableStock negative (current="
                        + inv.getAvailableStock() + ", delta=" + delta + ")");
            }
        }

        RLock lock = redissonClient.getLock("lock:product:" + productId);
        try {
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!acquired) throw new RuntimeException("Could not acquire lock for product: " + productId);

            // First deletion — before the MySQL write
            cacheService.deleteCache(productId);

            // MySQL update
            inventoryRepository.adjustStock(productId, delta);
            log.info("[Inventory] product:{} adjusted by delta={} reason={}", productId, delta, request.getReason());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock interrupted for product: " + productId);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }

        // Second deletion runs ~500ms later on background thread (after lock released)
        cacheService.scheduleSecondDeletion(productId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Product findActiveOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    /** Used by list queries — fetches stock from MySQL directly (no cache). */
    private ProductResponse toResponseWithStock(Product product) {
        ProductInventory inv = inventoryRepository.findByProductId(product.getId()).orElse(null);
        return toResponse(product, inv);
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
