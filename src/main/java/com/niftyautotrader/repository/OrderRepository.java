package com.niftyautotrader.repository;

import com.niftyautotrader.model.Order;
import com.niftyautotrader.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByStatusIn(List<OrderStatus> statuses);
    Optional<Order> findByBrokerOrderId(String brokerOrderId);
    List<Order> findByTradeIdOrderByCreatedAtDesc(Long tradeId);
}
