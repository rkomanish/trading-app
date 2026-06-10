package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String brokerOrderId; // assigned by Kite or paper broker

    @Column(nullable = false, length = 30)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Column(nullable = false)
    private int quantity;

    @Column(precision = 12, scale = 2)
    private BigDecimal price; // null = market order

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(precision = 12, scale = 2)
    private BigDecimal avgFillPrice;

    @Column(nullable = false)
    private ZonedDateTime createdAt;

    private ZonedDateTime updatedAt;

    private boolean paper;

    @Column(length = 500)
    private String rejectReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_id")
    private Trade trade;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("transitionedAt ASC")
    private List<OrderStateTransition> transitions = new ArrayList<>();

    // Getters & setters

    public Long getId() { return id; }

    public String getBrokerOrderId() { return brokerOrderId; }
    public void setBrokerOrderId(String brokerOrderId) { this.brokerOrderId = brokerOrderId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public OrderSide getSide() { return side; }
    public void setSide(OrderSide side) { this.side = side; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public BigDecimal getAvgFillPrice() { return avgFillPrice; }
    public void setAvgFillPrice(BigDecimal avgFillPrice) { this.avgFillPrice = avgFillPrice; }

    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }

    public ZonedDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(ZonedDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean isPaper() { return paper; }
    public void setPaper(boolean paper) { this.paper = paper; }

    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    public Trade getTrade() { return trade; }
    public void setTrade(Trade trade) { this.trade = trade; }

    public List<OrderStateTransition> getTransitions() { return transitions; }
}
