package com.example.flashsale.controller;

import com.example.flashsale.dto.product.*;
import com.example.flashsale.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /** Public — paginated, filter by ?category={id} or ?search={name} */
    @GetMapping
    public ResponseEntity<Page<ProductResponse>> listProducts(
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) String search,
            Pageable pageable) {
        return ResponseEntity.ok(productService.listProducts(category, search, pageable));
    }

    /** Public — detail + live stock (cache-aside) */
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    /** ADMIN — creates product + inventory row in one transaction */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.ok(productService.createProduct(request));
    }

    /** ADMIN — updates catalog fields; status=INACTIVE soft-deletes */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable Long id,
                                                         @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(productService.updateProduct(id, request));
    }

    /**
     * ADMIN — adjusts stock by delta (+N restock, -N correction/damaged).
     * Acquires lock:product:{id} and runs full delayed double deletion.
     */
    @PatchMapping("/{id}/inventory")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> adjustInventory(@PathVariable Long id,
                                                @Valid @RequestBody InventoryAdjustRequest request) {
        productService.adjustInventory(id, request);
        return ResponseEntity.noContent().build();
    }
}
