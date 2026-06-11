package com.niftyautotrader.service.paper;

import com.niftyautotrader.broker.Broker;
import com.niftyautotrader.broker.OrderRequest;
import com.niftyautotrader.broker.OrderResult;
import com.niftyautotrader.config.TradingProperties;
import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.OrderSide;
import com.niftyautotrader.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated broker used when app.trading.live-enabled=false.
 * Fills orders at last known price ± slippage. Maintains in-memory position map.
 *
 * Last price resolution order:
 *   1. lastPrices map (updated via updatePrice() from a tick feed), then
 *   2. latest 5m candle in the DB (kept fresh by CandleFetchScheduler), then
 *   3. the order's limit price.
 *
 * Tracks realized P&L, per-symbol entry prices, and trade count for the
 * paper trading dashboard.
 */
@Service
@ConditionalOnProperty(name = "app.trading.live-enabled", havingValue = "false", matchIfMissing = true)
public class PaperBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(PaperBroker.class);

    private final TradingProperties tradingProps;
    private final CandleRepository candleRepo;

    /** symbol -> net quantity (positive = long) */
    private final ConcurrentHashMap<String, Integer> positions = new ConcurrentHashMap<>();

    /** symbol -> last price (updated by market data service or manually) */
    private final ConcurrentHashMap<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    /** symbol -> entry price of the currently open position */
    private final ConcurrentHashMap<String, BigDecimal> entryPrices = new ConcurrentHashMap<>();

    private final AtomicInteger orderSeq = new AtomicInteger(1);

    private volatile BigDecimal realizedPnl = BigDecimal.ZERO;
    private volatile int totalPaperTrades = 0;

    public PaperBroker(TradingProperties tradingProps, CandleRepository candleRepo) {
        this.tradingProps = tradingProps;
        this.candleRepo = candleRepo;
    }

    @Override
    public OrderResult placeOrder(OrderRequest request) {
        BigDecimal marketPrice = getLastPrice(request.getSymbol());
        if (marketPrice.compareTo(BigDecimal.ZERO) == 0 && request.getLimitPrice() != null) {
            marketPrice = request.getLimitPrice();
        }

        BigDecimal fillPrice = applySlippage(marketPrice, request.getSide());
        String orderId = "PAPER-" + orderSeq.getAndIncrement();
        String symbol = request.getSymbol();

        // Update position and P&L tracking
        int delta = request.getSide() == OrderSide.BUY
            ? request.getQuantity()
            : -request.getQuantity();

        if (request.getSide() == OrderSide.BUY && !request.isClosingOrder()) {
            entryPrices.put(symbol, fillPrice);
        } else if (request.getSide() == OrderSide.SELL || request.isClosingOrder()) {
            BigDecimal entry = entryPrices.getOrDefault(symbol, fillPrice);
            BigDecimal tradePnl = fillPrice.subtract(entry)
                .multiply(BigDecimal.valueOf(request.getQuantity()));
            realizedPnl = realizedPnl.add(tradePnl);
            totalPaperTrades++;
            log.info("[PAPER] Trade P&L=₹{} | cumulative=₹{}", tradePnl, realizedPnl);
        }

        positions.merge(symbol, delta, Integer::sum);
        if (positions.getOrDefault(symbol, 0) == 0) {
            entryPrices.remove(symbol);
        }

        log.info("[PAPER] FILLED {} {} x{} @ ₹{} (slip ₹{}) orderId={}",
            request.getSide(), symbol, request.getQuantity(),
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
        BigDecimal tick = lastPrices.get(symbol);
        if (tick != null && tick.compareTo(BigDecimal.ZERO) > 0) {
            return tick;
        }
        // Fall back to the latest 5m candle in the DB (kept fresh by the scheduler)
        List<Candle> recent = candleRepo
            .findTop100BySymbolAndTimeframeOrderByOpenTimeDesc(symbol, "5m");
        return recent.isEmpty() ? BigDecimal.ZERO : recent.get(0).getClose();
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
        entryPrices.clear();
    }

    @Override
    public boolean isPaper() { return true; }

    @Override
    public String getName() { return "PaperBroker"; }

    @Override
    public Map<String, BigDecimal> getLastPrices(List<String> symbols) {
        Map<String, BigDecimal> result = new HashMap<>();
        symbols.forEach(s -> result.put(s, getLastPrice(s)));
        return result;
    }

    /** Called by MarketDataService on each tick/quote update */
    public void updatePrice(String symbol, BigDecimal price) {
        lastPrices.put(symbol, price);
    }

    // ── Dashboard accessors ──────────────────────────────────────────────────

    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public int getTotalPaperTrades()   { return totalPaperTrades; }
    public Map<String, BigDecimal> getEntryPrices() {
        return Collections.unmodifiableMap(entryPrices);
    }
}
