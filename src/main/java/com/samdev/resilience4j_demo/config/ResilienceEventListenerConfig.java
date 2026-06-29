package com.samdev.resilience4j_demo.config;

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

        // Also attach to any circuit breakers created after startup
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