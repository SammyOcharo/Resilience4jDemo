package com.samdev.resilience4j_demo.entity;

public enum OrderStatus {
    PENDING,
    PAYMENT_CONFIRMED,
    PAYMENT_FAILED,
    PAYMENT_SERVICE_UNAVAILABLE,
    RATE_LIMITED
}