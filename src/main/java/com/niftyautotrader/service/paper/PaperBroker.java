package com.niftyautotrader.service.paper;

import com.niftyautotrader.broker.Broker;
import com.niftyautotrader.broker.OrderRequest;
import com.niftyautotrader.broker.OrderResult;
import com.niftyautotrader.config.TradingProperties;
import com.niftyautotrader.model.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated broker used when app.trading.live-enabled=false.
 * Fills orders at last known price ± slippage.
 * Maintains in-memory position map.
 */
@Service
@ConditionalOnProperty(name = "app.trading.live-enabled", havingValue = "false", matchIfMissing = true)
public class PaperBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(PaperBroker.class);

    private final TradingProperties tradingProps;

    /** symbol -> net quantity (positive = long) */
    private final ConcurrentHashMap<String, Integer> positions = new ConcurrentHashMap<>();

    /** symbol -> last price (updated by market data service or manually) */
    private final ConcurrentHashMap<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    private final AtomicInteger orderSeq = new AtomicInteger(1);

    public PaperBroker(TradingProperties tradingProps) {
        this.tradingProps = tradingProps;
    }

    @Override
    public OrderResult placeOrder(OrderRequest request) {
        BigDecimal marketPrice = lastPrices.getOrDefault(request.getSymbol(), BigDecimal.ZERO);
        if (marketPrice.compareTo(BigDecimal.ZERO) == 0 && request.getLimitPrice() != null) {
            marketPrice = request.getLimitPrice();
        }

        BigDecimal fillPrice = applySlippage(marketPrice, request.getSide());
        String orderId = "PAPER-" + orderSeq.getAndIncrement();

        // Update position
        int delta = request.getSide() == OrderSide.BUY
            ? request.getQuantity()
            : -request.getQuantity();
        positions.merge(request.getSymbol(), delta, Integer::sum);

        log.info("[PAPER] FILLED {} {} x{} @ ₹{} (slip ₹{}) orderId={}",
            request.getSide(), request.getSymbol(), request.getQuantity(),
            fillPrice, fillPrice.subtract(marketPrice).abs(), orderId);

        return OrderResult.filled(orderId, fillPrice);
    }

    private BigDecimal applySlippage(BigDecimal price, OrderSide side) {
        if (price.compareTo(BigDecimal.ZERO) == 0) return price;
        BigDecimal slippagePct = BigDecimal.valueOf(tradingProps.getPaperSlippagePercent() / 100.0);
        BigDecimal slip = price.multiply(slippagePct).setScale(2, RoundingMode.HALF_UP);
        return side == OrderSide.BUY ? price.add(slip) : price.subtract(slip);
    }

    @Override
    public boolean cancelOrder(String brokerOrderId) {
        log.info("[PAPER] Cancel order: {}", brokerOrderId);
        return true; // paper orders cancel immediately
    }

    @Override
    public BigDecimal getLastPrice(String symbol) {
        return lastPrices.getOrDefault(symbol, BigDecimal.ZERO);
    }

    @Override
    public Map<String, Integer> getOpenPositions() {
        Map<String, Integer> open = new HashMap<>();
        positions.forEach((sym, qty) -> {
            if (qty != 0) open.put(sym, qty);
        });
        return open;
    }

    @Override
    public void cancelAllAndFlatten() {
        log.warn("[PAPER] Kill switch: cancelling all and flattening positions");
        positions.forEach((symbol, qty) -> {
            if (qty != 0) {
                OrderRequest exit = OrderRequest.builder()
                    .symbol(symbol)
                    .side(qty > 0 ? OrderSide.SELL : OrderSide.BUY)
                    .quantity(Math.abs(qty))
                    .strategyName("KILL_SWITCH")
                    .closingOrder(true)
                    .build();
                placeOrder(exit);
            }
        });
        positions.clear();
    }

    @Override
    public boolean isPaper() { return true; }

    @Override
    public String getName() { return "PaperBroker"; }

    @Override
    public Map<String, BigDecimal> getLastPrices(List<String> symbols) {
        Map<String, BigDecimal> result = new HashMap<>();
        symbols.forEach(s -> result.put(s, lastPrices.getOrDefault(s, BigDecimal.ZERO)));
        return result;
    }

    /** Called by MarketDataService on each tick/quote update */
    public void updatePrice(String symbol, BigDecimal price) {
        lastPrices.put(symbol, price);
    }
}
