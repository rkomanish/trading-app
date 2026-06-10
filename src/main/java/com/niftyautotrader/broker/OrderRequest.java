package com.niftyautotrader.broker;

import com.niftyautotrader.model.OrderSide;

import java.math.BigDecimal;

/**
 * Immutable value object passed to RiskEngine and then to Broker.
 * Use the builder to construct.
 */
public final class OrderRequest {

    private final String symbol;
    private final OrderSide side;
    private final int quantity;
    private final BigDecimal limitPrice;  // null = market order
    private final BigDecimal stopLossPrice;
    private final String strategyName;
    private final Long signalId;
    /** True = selling to CLOSE an existing long. False = opening a new position. */
    private final boolean closingOrder;

    private OrderRequest(Builder b) {
        this.symbol = b.symbol;
        this.side = b.side;
        this.quantity = b.quantity;
        this.limitPrice = b.limitPrice;
        this.stopLossPrice = b.stopLossPrice;
        this.strategyName = b.strategyName;
        this.signalId = b.signalId;
        this.closingOrder = b.closingOrder;
    }

    public String getSymbol() { return symbol; }
    public OrderSide getSide() { return side; }
    public int getQuantity() { return quantity; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public String getStrategyName() { return strategyName; }
    public Long getSignalId() { return signalId; }
    public boolean isClosingOrder() { return closingOrder; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String symbol;
        private OrderSide side;
        private int quantity;
        private BigDecimal limitPrice;
        private BigDecimal stopLossPrice;
        private String strategyName;
        private Long signalId;
        private boolean closingOrder = false;

        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        public Builder side(OrderSide side) { this.side = side; return this; }
        public Builder quantity(int quantity) { this.quantity = quantity; return this; }
        public Builder limitPrice(BigDecimal limitPrice) { this.limitPrice = limitPrice; return this; }
        public Builder stopLossPrice(BigDecimal stopLossPrice) { this.stopLossPrice = stopLossPrice; return this; }
        public Builder strategyName(String strategyName) { this.strategyName = strategyName; return this; }
        public Builder signalId(Long signalId) { this.signalId = signalId; return this; }
        public Builder closingOrder(boolean closingOrder) { this.closingOrder = closingOrder; return this; }
        public OrderRequest build() { return new OrderRequest(this); }
    }
}
