package com.platform.order.repository;

import com.platform.order.entity.Order;
import com.platform.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    List<Order> findByCustomerId(String customerId);

    List<Order> findByStatus(OrderStatus status);
}
