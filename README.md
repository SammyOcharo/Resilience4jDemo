# Resilience4jDemo
# Resilience4j Spring Boot Microservices — Complete Implementation Guide

A real-world walkthrough of all 5 Resilience4j fault-tolerance patterns implemented in a Spring Boot 3 `order-service` that saves orders to PostgreSQL and calls an external Payment API.

---

## Project Overview

**Scenario:** An `order-service` that:
- Persists orders to **PostgreSQL** (protected by Bulkhead)
- Calls an **external Payment API** (protected by Circuit Breaker + Retry + Timeout + Rate Limiter)

**External API stand-in:** [JSONPlaceholder](https://jsonplaceholder.typicode.com) — a real live public REST API used to simulate a payment gateway without needing API keys.

---

## Table of Contents

1. [Project Setup & Dependencies](#1-project-setup--dependencies)
2. [PostgreSQL Config + Entity + Repository](#2-postgresql-config--entity--repository)
3. [External Payment API Client](#3-external-payment-api-client)
4. [Circuit Breaker](#4-circuit-breaker)
5. [Retry + Timeout](#5-retry--timeout)
6. [Rate Limiter](#6-rate-limiter)
7. [Bulkhead](#7-bulkhead)
8. [Actuator Metrics & Observability](#8-actuator-metrics--observability)
9. [Project Structure](#9-project-structure)
10. [Key Lessons](#10-key-lessons)

---

## 1. Project Setup & Dependencies

### `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.sam</groupId>
    <artifactId>order-service</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>order-service</name>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- Web — exposes REST endpoints -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- JPA + PostgreSQL — database layer -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Resilience4j — all 5 patterns -->
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>2.1.0</version>
        </dependency>

        <!-- AOP — CRITICAL: without this, annotations are silently ignored -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>

        <!-- Actuator — /actuator/health shows circuit breaker state -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Micrometer Prometheus — exposes CB state as scrapable metrics -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- Lombok — reduces boilerplate -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

> **Critical:** `spring-boot-starter-aop` is the most commonly missed dependency. Resilience4j annotations are AOP proxies — without this, `@CircuitBreaker`, `@Retry` etc. are silently bypassed with no error thrown.

---

## 2. PostgreSQL Config + Entity + Repository

### `src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: order-service

  datasource:
    url: jdbc:postgresql://localhost:5432/order_db
    username: postgres
    password: yourpassword
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 3000
      idle-timeout: 600000

  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true

management:
  endpoints:
    web:
      exposure:
        include: health, metrics, prometheus, info
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
    ratelimiters:
      enabled: true
    bulkheads:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        resilience4j.circuitbreaker.calls: true

resilience4j:
  circuitbreaker:
    instances:
      paymentApi:
        registerHealthIndicator: true
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - org.springframework.web.client.ResourceAccessException

  retry:
    instances:
      paymentApi:
        maxAttempts: 3
        waitDuration: 1000
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - java.io.IOException
          - org.springframework.web.client.ResourceAccessException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - io.github.resilience4j.circuitbreaker.CallNotPermittedException

  timelimiter:
    instances:
      paymentApi:
        timeoutDuration: 3s
        cancelRunningFuture: true

  ratelimiter:
    instances:
      paymentApi:
        registerHealthIndicator: true
        limitForPeriod: 5
        limitRefreshPeriod: 1m
        timeoutDuration: 5s

  bulkhead:
    instances:
      orderDatabase:
        maxConcurrentCalls: 20
        maxWaitDuration: 500ms

payment:
  api:
    base-url: https://jsonplaceholder.typicode.com
```

### `entity/Order.java`

```java
package com.sam.orderservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    private String paymentReference;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
```

### `entity/OrderStatus.java`

```java
package com.sam.orderservice.entity;

public enum OrderStatus {
    PENDING,
    PAYMENT_CONFIRMED,
    PAYMENT_FAILED,
    PAYMENT_SERVICE_UNAVAILABLE,  // circuit breaker is OPEN
    RATE_LIMITED                   // rate limiter rejected the call
}
```

> **Real-world note:** `PAYMENT_SERVICE_UNAVAILABLE` vs `PAYMENT_FAILED` is a meaningful distinction in fintech. One means the payment gateway is down (retry later), the other means the payment was attempted and rejected (don't retry without user action).

### `repository/OrderRepository.java`

```java
package com.sam.orderservice.repository;

import com.sam.orderservice.entity.Order;
import com.sam.orderservice.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByCustomerName(String customerName);
    List<Order> findByStatus(OrderStatus status);
}
```

> **PostgreSQL gotcha:** When you add a new `OrderStatus` enum value, PostgreSQL's check constraint on the `status` column is not automatically updated by Hibernate's `ddl-auto: update`. You must update it manually:
> ```sql
> ALTER TABLE orders DROP CONSTRAINT orders_status_check;
> ALTER TABLE orders ADD CONSTRAINT orders_status_check
> CHECK (status IN ('PENDING','PAYMENT_CONFIRMED','PAYMENT_FAILED',
>                   'PAYMENT_SERVICE_UNAVAILABLE','RATE_LIMITED'));
> ```
> This is why production systems use **Flyway or Liquibase** for versioned schema migrations instead of `ddl-auto`.

---

## 3. External Payment API Client

### `config/RestTemplateConfig.java`

```java
package com.sam.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);  // fail fast if server unreachable
        factory.setReadTimeout(5000);     // triggers slowCallDurationThreshold in CB
        return new RestTemplate(factory);
    }
}
```

> `connectTimeout`/`readTimeout` are socket-level timeouts. Resilience4j's `TimeLimiter` wraps the entire call. You need **both** — they serve different purposes.

### `dto/PaymentRequest.java`

```java
package com.sam.orderservice.dto;

import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentRequest {
    private String orderId;
    private String customerName;
    private BigDecimal amount;
    private String currency;
}
```

### `dto/PaymentResponse.java`

```java
package com.sam.orderservice.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentResponse {
    private String paymentReference;
    private String status;
    private String message;
}
```

### `dto/OrderRequest.java`

```java
package com.sam.orderservice.dto;

import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderRequest {
    private String customerName;
    private String productName;
    private BigDecimal amount;
}
```

---

## 4. Circuit Breaker

**What it does:** Stops sending requests to a failing service. After 50% of 10 calls fail, the circuit OPENS and subsequent calls are rejected instantly without making an HTTP call.

```
CLOSED → (50% failure rate over 10 calls) → OPEN → (wait 10s) → HALF-OPEN → (3 test calls succeed) → CLOSED
```

Circuit Breaker lives on the **outer method** — it sees the final outcome after all retries are exhausted.

---

## 5. Retry + Timeout

**What they do:**
- **Retry:** Automatically retries failed calls with exponential backoff (1s → 2s → 4s)
- **TimeLimiter:** Cancels any call that takes longer than 3 seconds per attempt

**Key insight — annotation ordering matters:**

Stacking `@CircuitBreaker + @TimeLimiter + @Retry` on one method causes the TimeLimiter to wrap all retries combined (one 3s window for everything). The correct approach is **two separate methods**:

```
Outer method: @CircuitBreaker
    ↓ delegates to ↓
Inner method: @Retry + @TimeLimiter
```

This gives each retry attempt its own 3s timeout window.

**Self-injection via `ApplicationContext`** is required to call the inner method through the Spring AOP proxy — calling `this.method()` directly bypasses all AOP annotations:

```java
private PaymentApiClient self() {
    return applicationContext.getBean(PaymentApiClient.class);
}
```

---

## 6. Rate Limiter

**What it does:** Allows only 5 calls to the Payment API per minute. Requests beyond that wait up to 5s for a permit, then hit the fallback.

**Real-world use case:** External payment providers (M-Pesa, Stripe, Africa's Talking) all enforce rate limits. Without a client-side rate limiter, failed calls trigger retries which amplifies the problem and can get you blacklisted.

**Observed behaviour from testing:**
```
10 parallel requests fired simultaneously
→ Orders 1–5:   PAYMENT_CONFIRMED  (got a permit immediately)
→ Orders 6–10:  RATE_LIMITED       (waited 5s, no permit, hit fallback)
```

---

## 7. Bulkhead

**What it does:** Limits concurrent access to the database layer to 20 threads. Protects the HikariCP connection pool from being exhausted under traffic spikes.

**Why a separate `OrderDatabaseService`:** The Bulkhead protects a different resource (DB connections) than the Payment API patterns. Separating it keeps concerns clean and makes the boundary explicit.

**Semaphore vs ThreadPool Bulkhead:**
- `SEMAPHORE` (default) — uses the calling thread. Use with blocking JDBC/JPA calls.
- `THREADPOOL` — creates separate threads. Do NOT use with JPA — Hibernate's `EntityManager` is not thread-safe across threads.

```
Without Bulkhead:
  500 concurrent requests → all 500 grab DB connections → pool exhausted → app unresponsive

With Bulkhead:
  500 concurrent requests → only 20 enter DB layer → 480 rejected gracefully → app stays alive
```

---

## 8. Actuator Metrics & Observability

### `ResilienceMetricsController.java`

```java
package com.sam.orderservice.controller;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getResilienceStatus() {
        Map<String, Object> status = new HashMap<>();

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

        rateLimiterRegistry.getAllRateLimiters().forEach(rl -> {
            Map<String, Object> rlStatus = new HashMap<>();
            rlStatus.put("availablePermissions", rl.getMetrics().getAvailablePermissions());
            rlStatus.put("numberOfWaitingThreads", rl.getMetrics().getNumberOfWaitingThreads());
            status.put("rateLimiter_" + rl.getName(), rlStatus);
        });

        bulkheadRegistry.getAllBulkheads().forEach(bh -> {
            Map<String, Object> bhStatus = new HashMap<>();
            bhStatus.put("availableConcurrentCalls", bh.getMetrics().getAvailableConcurrentCalls());
            bhStatus.put("maxAllowedConcurrentCalls", bh.getMetrics().getMaxAllowedConcurrentCalls());
            status.put("bulkhead_" + bh.getName(), bhStatus);
        });

        return ResponseEntity.ok(status);
    }
}
```

### `config/ResilienceEventListenerConfig.java`

```java
package com.sam.orderservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

@Slf4j
@Configuration
public class ResilienceEventListenerConfig {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ResilienceEventListenerConfig(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @PostConstruct
    public void attachEventListeners() {
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(this::attachListeners);
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> attachListeners(event.getAddedEntry()));
    }

    private void attachListeners(CircuitBreaker cb) {
        cb.getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "🔌 Circuit Breaker [{}] state changed: {} → {}",
                        event.getCircuitBreakerName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()
                ))
                .onFailureRateExceeded(event -> log.error(
                        "🔴 Circuit Breaker [{}] failure rate exceeded: {}%",
                        event.getCircuitBreakerName(),
                        event.getEventType()
                ))
                .onCallNotPermitted(event -> log.warn(
                        "🚫 Circuit Breaker [{}] blocked a call — circuit is OPEN",
                        event.getCircuitBreakerName()
                ))
                .onSuccess(event -> log.debug(
                        "✅ Circuit Breaker [{}] recorded a success ({}ms)",
                        event.getCircuitBreakerName(),
                        event.getElapsedDuration().toMillis()
                ))
                .onError(event -> log.error(
                        "❌ Circuit Breaker [{}] recorded a failure: {}",
                        event.getCircuitBreakerName(),
                        event.getThrowable().getMessage()
                ));
    }
}
```

### Useful curl commands

```bash
# All patterns status in one call
curl -s http://localhost:8080/api/resilience/status | python3 -m json.tool

# Full health check including CB state
curl -s http://localhost:8080/actuator/health | python3 -m json.tool

# Rate limiter available permits
curl -s http://localhost:8080/actuator/metrics/resilience4j.ratelimiter.available.permissions \
  | python3 -m json.tool

# Circuit breaker state
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state \
  | python3 -m json.tool

# Watch status live (updates every second)
watch -n 1 'curl -s http://localhost:8080/api/resilience/status | python3 -m json.tool'
```

### Test scenarios

```bash
# Normal flow
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"Sam","productName":"Laptop","amount":85000.00}'

# Rate limiter — fire 10 at once, 5 get RATE_LIMITED
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/orders \
    -H "Content-Type: application/json" \
    -d '{"customerName":"Sam","productName":"Laptop","amount":85000.00}' &
done
wait

# Circuit breaker — break the URL in application.yml, fire 12 requests
# After request 10 → circuit OPENS, requests 11-12 return instantly
for i in {1..12}; do
  curl -s -X POST http://localhost:8080/api/orders \
    -H "Content-Type: application/json" \
    -d '{"customerName":"Sam","productName":"Laptop","amount":85000.00}'
  echo ""
done
```

---

## 9. Project Structure

```
order-service/
├── src/main/java/com/sam/orderservice/
│   ├── OrderServiceApplication.java
│   ├── client/
│   │   └── PaymentApiClient.java          # All payment API patterns live here
│   ├── config/
│   │   ├── RestTemplateConfig.java        # Socket-level timeouts
│   │   └── ResilienceEventListenerConfig.java  # CB state change logging
│   ├── controller/
│   │   ├── OrderController.java
│   │   └── ResilienceMetricsController.java    # /api/resilience/status
│   ├── dto/
│   │   ├── OrderRequest.java
│   │   ├── PaymentRequest.java
│   │   └── PaymentResponse.java
│   ├── entity/
│   │   ├── Order.java
│   │   └── OrderStatus.java
│   ├── repository/
│   │   └── OrderRepository.java
│   └── service/
│       ├── OrderDatabaseService.java      # Bulkhead lives here
│       └── OrderService.java              # Orchestrates order + payment flow
└── src/main/resources/
    └── application.yml
```

---

## 10. Key Lessons

### Pattern placement
| Pattern | Where it lives | Why |
|---|---|---|
| Circuit Breaker | `PaymentApiClient.processPayment()` outer method | Sees final outcome after all retries |
| Retry | `PaymentApiClient.processPaymentWithRetry()` inner method | Each attempt gets its own timeout |
| TimeLimiter | `PaymentApiClient.processPaymentWithRetry()` inner method | Per-attempt timeout, not per-call |
| Rate Limiter | `PaymentApiClient.processPayment()` outermost | Throttle before wasting CB/retry budget |
| Bulkhead | `OrderDatabaseService` methods | Protects DB connections, separate resource |

### Annotation execution order (outside-in)
```
RateLimiter → CircuitBreaker → Retry → TimeLimiter → actual HTTP call
```

### AOP self-invocation problem
Calling `this.method()` within the same Spring bean bypasses AOP proxies — all Resilience4j annotations on the called method are silently ignored. Fix: inject `ApplicationContext` and call `applicationContext.getBean(PaymentApiClient.class).method()`.

### Two fallback methods for Circuit Breaker
```java
// Called when circuit is OPEN (no HTTP call attempted)
public ... fallback(Request request, CallNotPermittedException e) { }

// Called when actual call failed (network error, timeout etc.)
public ... fallback(Request request, Exception e) { }
```
Resilience4j picks the most specific exception type. Distinguishing these two cases matters in production — open circuit means retry later, actual error means investigate.

### TimeLimiter requires CompletableFuture
`@TimeLimiter` only works on methods returning `CompletableFuture<T>`. This is mandatory — Resilience4j needs an async handle to cancel the running thread when the timeout fires.

### Bulkhead type for JPA
Always use `SEMAPHORE` bulkhead (default) with JPA/Hibernate. Never use `THREADPOOL` — Hibernate's `EntityManager` is not thread-safe and will throw errors when accessed from multiple threads.

### Circuit Breaker needs a full sliding window before calculating failure rate
With `slidingWindowSize: 10`, the failure rate shows `-1.0%` until 10 calls have been recorded. The circuit will not open on the first failure — by design.

### PostgreSQL enum check constraints
`ddl-auto: update` creates a check constraint from your enum values at table creation time. Adding new enum values in Java does not update the PostgreSQL constraint automatically. Always update it manually or use Flyway/Liquibase migrations in production.

---

## Pattern Summary

| Pattern | Protects Against | Key Config |
|---|---|---|
| Circuit Breaker | Cascading failures | `failureRateThreshold: 50`, `slidingWindowSize: 10` |
| Retry | Transient network errors | `maxAttempts: 3`, exponential backoff |
| TimeLimiter | Slow external APIs | `timeoutDuration: 3s` per attempt |
| Rate Limiter | Overwhelming external APIs | `limitForPeriod: 5` per minute |
| Bulkhead | DB connection pool exhaustion | `maxConcurrentCalls: 20` |
