package com.samdev.resilience4j_demo.client;


import com.samdev.resilience4j_demo.dto.PaymentRequest;
import com.samdev.resilience4j_demo.dto.PaymentResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentApiClient {

    private final RestTemplate restTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Value("${payment.api.base-url}")
    private String baseUrl;

    private PaymentApiClient self() {
        return applicationContext.getBean(PaymentApiClient.class);
    }

    /**
     * Execution order outside-in:
     *   RateLimiter → CircuitBreaker → Retry → TimeLimiter → actual call
     *
     * RateLimiter is outermost — if no permit available, reject immediately
     * before wasting any circuit breaker or retry budget.
     */
    @RateLimiter(name = "paymentApi", fallbackMethod = "rateLimitFallback")
    @CircuitBreaker(name = "paymentApi", fallbackMethod = "processPaymentFallback")
    public CompletableFuture<PaymentResponse> processPayment(PaymentRequest request) {
        return self().processPaymentWithRetry(request);
    }

    @Retry(name = "paymentApi")
    @TimeLimiter(name = "paymentApi")
    public CompletableFuture<PaymentResponse> processPaymentWithRetry(PaymentRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Attempting Payment API call for order: {} amount: {}",
                    request.getOrderId(), request.getAmount());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<PaymentRequest> httpEntity = new HttpEntity<>(request, headers);

            var response = restTemplate.postForObject(
                    baseUrl + "/posts",
                    httpEntity,
                    PaymentResponse.class
            );

            log.info("Payment API success for order: {}", request.getOrderId());
            return response;
        });
    }

    // Rate limit exceeded — no permit available within timeoutDuration
    public CompletableFuture<PaymentResponse> rateLimitFallback(
            PaymentRequest request, RequestNotPermitted e) {

        log.warn("Rate limit exceeded for order: {}. Too many requests to Payment API.",
                request.getOrderId());

        return CompletableFuture.completedFuture(
                PaymentResponse.builder()
                        .paymentReference("RATE_LIMITED")
                        .status("RATE_LIMITED")
                        .message("Payment API rate limit reached. Please retry in a moment.")
                        .build()
        );
    }

    // Circuit is OPEN
    public CompletableFuture<PaymentResponse> processPaymentFallback(
            PaymentRequest request, CallNotPermittedException e) {

        log.warn("Circuit OPEN for order: {}. Skipping payment call entirely.",
                request.getOrderId());

        return CompletableFuture.completedFuture(
                PaymentResponse.builder()
                        .paymentReference("UNAVAILABLE")
                        .status("CIRCUIT_OPEN")
                        .message("Payment service unavailable. Order queued for retry.")
                        .build()
        );
    }

    // All retries exhausted
    public CompletableFuture<PaymentResponse> processPaymentFallback(
            PaymentRequest request, Exception e) {

        log.error("All retry attempts failed for order: {}. Final error: {}",
                request.getOrderId(), e.getMessage());

        return CompletableFuture.completedFuture(
                PaymentResponse.builder()
                        .paymentReference("FAILED")
                        .status("PAYMENT_ERROR")
                        .message("Payment failed after retries: " + e.getMessage())
                        .build()
        );
    }
}