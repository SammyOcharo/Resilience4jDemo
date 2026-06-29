package com.samdev.resilience4j_demo.service;


import com.samdev.resilience4j_demo.client.PaymentApiClient;
import com.samdev.resilience4j_demo.dto.OrderRequest;
import com.samdev.resilience4j_demo.dto.PaymentRequest;
import com.samdev.resilience4j_demo.dto.PaymentResponse;
import com.samdev.resilience4j_demo.entity.Order;
import com.samdev.resilience4j_demo.entity.OrderStatus;
import com.samdev.resilience4j_demo.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final PaymentApiClient paymentApiClient;

    @Transactional
    public Order createOrder(OrderRequest request) {
        // Save order as PENDING first — never lose an order
        Order order = Order.builder()
                .customerName(request.getCustomerName())
                .productName(request.getProductName())
                .amount(request.getAmount())
                .status(OrderStatus.PENDING)
                .build();

        order = orderRepository.save(order);
        log.info("Order {} saved with status PENDING", order.getId());

        PaymentRequest paymentRequest = PaymentRequest.builder()
                .orderId(order.getId().toString())
                .customerName(request.getCustomerName())
                .amount(request.getAmount())
                .currency("KES")
                .build();

        try {
            // .get() blocks until the CompletableFuture completes
            // In production you'd handle this async — but for learning,
            // blocking here keeps the flow easy to follow in logs
            CompletableFuture<PaymentResponse> future =
                    paymentApiClient.processPayment(paymentRequest);

            PaymentResponse paymentResponse = future.get();

            if ("CIRCUIT_OPEN".equals(paymentResponse.getStatus())) {
                order.setStatus(OrderStatus.PAYMENT_SERVICE_UNAVAILABLE);
            } else if ("PAYMENT_ERROR".equals(paymentResponse.getStatus())) {
                order.setStatus(OrderStatus.PAYMENT_FAILED);
            } else if ("RATE_LIMITED".equals(paymentResponse.getStatus())) {
                order.setStatus(OrderStatus.RATE_LIMITED);
            } else {
                order.setStatus(OrderStatus.PAYMENT_CONFIRMED);
                order.setPaymentReference(paymentResponse.getPaymentReference());
            }

        } catch (Exception e) {
            log.error("Unexpected error processing payment for order {}: {}",
                    order.getId(), e.getMessage());
            order.setStatus(OrderStatus.PAYMENT_FAILED);
        }

        return orderRepository.save(order);
    }

    public Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found: " + id));
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }
}