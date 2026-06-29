package com.samdev.resilience4j_demo.controller;


import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.github.resilience4j.bulkhead.BulkheadRegistry;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/resilience")
@RequiredArgsConstructor
public class ResilienceMetricsController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    /**
     * Single endpoint showing all pattern states.
     * In production, you'd feed this into Grafana/Datadog.
     * Here it gives you a real-time snapshot during testing.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getResilienceStatus() {
        Map<String, Object> status = new HashMap<>();

        // Circuit Breaker state
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            CircuitBreaker.Metrics metrics = cb.getMetrics();
            Map<String, Object> cbStatus = new HashMap<>();
            cbStatus.put("state", cb.getState().toString());
            cbStatus.put("failureRate", metrics.getFailureRate() + "%");
            cbStatus.put("slowCallRate", metrics.getSlowCallRate() + "%");
            cbStatus.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
            cbStatus.put("failedCalls", metrics.getNumberOfFailedCalls());
            cbStatus.put("successfulCalls", metrics.getNumberOfSuccessfulCalls());
            cbStatus.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
            status.put("circuitBreaker_" + cb.getName(), cbStatus);
        });

        // Retry metrics
        retryRegistry.getAllRetries().forEach(retry -> {
            Map<String, Object> retryStatus = new HashMap<>();
            retryStatus.put("successfulCallsWithRetry",
                    retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt());
            retryStatus.put("failedCallsWithRetry",
                    retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt());
            retryStatus.put("failedCallsWithoutRetry",
                    retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt());
            status.put("retry_" + retry.getName(), retryStatus);
        });

        // Rate Limiter metrics
        rateLimiterRegistry.getAllRateLimiters().forEach(rl -> {
            Map<String, Object> rlStatus = new HashMap<>();
            rlStatus.put("availablePermissions",
                    rl.getMetrics().getAvailablePermissions());
            rlStatus.put("numberOfWaitingThreads",
                    rl.getMetrics().getNumberOfWaitingThreads());
            status.put("rateLimiter_" + rl.getName(), rlStatus);
        });

        // Bulkhead metrics
        bulkheadRegistry.getAllBulkheads().forEach(bh -> {
            Map<String, Object> bhStatus = new HashMap<>();
            bhStatus.put("availableConcurrentCalls",
                    bh.getMetrics().getAvailableConcurrentCalls());
            bhStatus.put("maxAllowedConcurrentCalls",
                    bh.getMetrics().getMaxAllowedConcurrentCalls());
            status.put("bulkhead_" + bh.getName(), bhStatus);
        });

        return ResponseEntity.ok(status);
    }
}