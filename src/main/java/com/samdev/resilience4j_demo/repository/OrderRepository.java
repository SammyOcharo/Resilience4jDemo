package com.samdev.resilience4j_demo.repository;

import com.samdev.resilience4j_demo.entity.Order;
import com.samdev.resilience4j_demo.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerName(String customerName);

    List<Order> findByStatus(OrderStatus status);
}
