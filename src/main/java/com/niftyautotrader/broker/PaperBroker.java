package com.niftyautotrader.broker;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paper trading broker — simulates fills using the latest DB candle price.
 *
 * Active when app.trading.live-enabled=false (default).
 * All fills are immediate at last known price + configured slippage.
 * Positions and orders are stored in-memory; they reset on restart.
 */
@Service
@ConditionalOnProperty(name = "app.trading.live-enabled", havingValue = "false", matchIfMissing = true)
public class PaperBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(PaperBroker.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CandleRepository candleRepo;
    private final double slippagePct;

    // in-memory state
    private final Map<String, Integer> positions = new ConcurrentHashMap<>();
    private final AtomicInteger orderIdSeq = new AtomicInteger(10000);

    // paper P&L tracking
    private final Map<String, BigDecimal> entryPrices = new ConcurrentHashMap<>();
    private volatile BigDecimal realizedPnl = BigDecimal.ZERO;
    private volatile int totalPaperTrades = 0;

    public PaperBroker(CandleRepository candleRepo) {
        this.candleRepo = candleRepo;
        this.slippagePct = 0.0002; // 0.02% per side
        log.info("PaperBroker initialized — live trading is DISABLED");
    }

    @Override
    public OrderResult placeOrder(OrderRequest request) {
        String brokerId = "PAPER-" + orderIdSeq.incrementAndGet();
        BigDecimal lastPrice = getLastPrice(request.getSymbol());

        if (lastPrice == null || lastPrice.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("PaperBroker: no price data for {}. Order REJECTED.", request.getSymbol());
            return OrderResult.rejected("No price data for symbol: " + request.getSymbol());
        }

        // Apply slippage: buy fills higher, sell fills lower
        double slip = request.isClosingOrder() ? -slippagePct : slippagePct;
        BigDecimal fillPrice = lastPrice.multiply(BigDecimal.valueOf(1.0 + slip))
            .setScale(2, java.math.RoundingMode.HALF_UP);

        // Update simulated position
        int qty = request.getQuantity();
        String symbol = request.getSymbol();

        if (!request.isClosingOrder()) {
            positions.merge(symbol, qty, Integer::sum);
            entryPrices.put(symbol, fillPrice);
            log.info("PAPER BUY  {} × {} @ ₹{} [id={}]", qty, symbol, fillPrice, brokerId);
        } else {
            BigDecimal entry = entryPrices.getOrDefault(symbol, fillPrice);
            BigDecimal tradePnl = fillPrice.subtract(entry).multiply(BigDecimal.valueOf(qty));
            realizedPnl = realizedPnl.add(tradePnl);
            totalPaperTrades++;
            positions.merge(symbol, -qty, Integer::sum);
            if (positions.getOrDefault(symbol, 0) <= 0) {
                positions.remove(symbol);
                entryPrices.remove(symbol);
            }
            log.info("PAPER SELL {} × {} @ ₹{} | Trade P&L=₹{} | Total P&L=₹{} [id={}]",
                qty, symbol, fillPrice, tradePnl, realizedPnl, brokerId);
        }

        return OrderResult.filled(brokerId, fillPrice);
    }

    @Override
    public boolean cancelOrder(String brokerOrderId) {
        log.info("PAPER: cancel order {}", brokerOrderId);
        return true; // paper orders are always instantly cancellable
    }

    @Override
    public BigDecimal getLastPrice(String symbol) {
        ZonedDateTime cutoff = ZonedDateTime.now(IST).minusHours(6);
        List<Candle> recent = candleRepo
            .findTop100BySymbolAndTimeframeOrderByOpenTimeDesc(symbol, "5m");
        if (recent.isEmpty()) return BigDecimal.ZERO;
        return recent.get(0).getClose();
    }

    @Override
    public Map<String, Integer> getOpenPositions() {
        return Collections.unmodifiableMap(positions);
    }

    @Override
    public void cancelAllAndFlatten() {
        log.warn("PAPER: cancelAllAndFlatten — clearing {} positions", positions.size());
        positions.keySet().forEach(symbol -> {
            Integer qty = positions.get(symbol);
            if (qty != null && qty > 0) {
                BigDecimal price = getLastPrice(symbol);
                BigDecimal entry = entryPrices.getOrDefault(symbol, price);
                BigDecimal pnl = price.subtract(entry).multiply(BigDecimal.valueOf(qty));
                realizedPnl = realizedPnl.add(pnl);
                totalPaperTrades++;
                log.info("PAPER square-off: {} × {} @ ₹{} P&L=₹{}", qty, symbol, price, pnl);
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

    public BigDecimal getRealizedPnl()  { return realizedPnl; }
    public int getTotalPaperTrades()    { return totalPaperTrades; }
    public Map<String, BigDecimal> getEntryPrices() { return Collections.unmodifiableMap(entryPrices); }
}
