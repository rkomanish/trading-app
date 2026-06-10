package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

@Entity
@Table(name = "order_state_transitions")
public class OrderStateTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus toStatus;

    @Column(nullable = false)
    private ZonedDateTime transitionedAt;

    @Column(length = 500)
    private String reason;

    // Getters & setters

    public Long getId() { return id; }

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public OrderStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(OrderStatus fromStatus) { this.fromStatus = fromStatus; }

    public OrderStatus getToStatus() { return toStatus; }
    public void setToStatus(OrderStatus toStatus) { this.toStatus = toStatus; }

    public ZonedDateTime getTransitionedAt() { return transitionedAt; }
    public void setTransitionedAt(ZonedDateTime transitionedAt) { this.transitionedAt = transitionedAt; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
