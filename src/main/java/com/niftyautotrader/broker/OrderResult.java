package com.niftyautotrader.broker;

import com.niftyautotrader.model.OrderStatus;

import java.math.BigDecimal;

public final class OrderResult {

    private final boolean success;
    private final String brokerOrderId;
    private final OrderStatus status;
    private final BigDecimal fillPrice;
    private final String errorMessage;

    private OrderResult(boolean success, String brokerOrderId, OrderStatus status,
                        BigDecimal fillPrice, String errorMessage) {
        this.success = success;
        this.brokerOrderId = brokerOrderId;
        this.status = status;
        this.fillPrice = fillPrice;
        this.errorMessage = errorMessage;
    }

    public static OrderResult filled(String brokerOrderId, BigDecimal fillPrice) {
        return new OrderResult(true, brokerOrderId, OrderStatus.FILLED, fillPrice, null);
    }

    public static OrderResult rejected(String reason) {
        return new OrderResult(false, null, OrderStatus.REJECTED, null, reason);
    }

    public static OrderResult submitted(String brokerOrderId) {
        return new OrderResult(true, brokerOrderId, OrderStatus.SUBMITTED, null, null);
    }

    public boolean isSuccess() { return success; }
    public String getBrokerOrderId() { return brokerOrderId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getFillPrice() { return fillPrice; }
    public String getErrorMessage() { return errorMessage; }
}
