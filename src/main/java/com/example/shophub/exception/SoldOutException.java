package com.example.shophub.exception;

public class SoldOutException extends RuntimeException {

    private final Long productId;

    public SoldOutException(Long productId) {
        super("Product " + productId + " is sold out");
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
