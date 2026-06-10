package com.niftyautotrader.broker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Single interface for all order routing — paper and live share this contract.
 * All code outside the broker package ONLY talks to this interface.
 */
public interface Broker {

    /** Place an order. The order has already been risk-approved by RiskEngine. */
    OrderResult placeOrder(OrderRequest request);

    /** Cancel an open order by broker order ID. */
    boolean cancelOrder(String brokerOrderId);

    /** Get current market price for a symbol. */
    BigDecimal getLastPrice(String symbol);

    /** Get all currently open positions: symbol -> net quantity */
    Map<String, Integer> getOpenPositions();

    /** Cancel all open orders and flatten all positions (kill switch). */
    void cancelAllAndFlatten();

    /** Whether this is a paper (simulated) broker. */
    boolean isPaper();

    /** Human-readable name for logging/UI. */
    String getName();

    /** Get last known prices for a batch of symbols */
    Map<String, BigDecimal> getLastPrices(List<String> symbols);
}
