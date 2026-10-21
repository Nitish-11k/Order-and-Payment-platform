package com.platform.inventory.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String sku) {
        super("Product not found with SKU: " + sku);
    }
}
