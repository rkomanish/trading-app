package com.niftyautotrader.service.strategy;

import com.niftyautotrader.broker.Broker;
import com.niftyautotrader.broker.OrderRequest;
import com.niftyautotrader.model.*;
import com.niftyautotrader.repository.CandleRepository;
import com.niftyautotrader.repository.SignalRepository;
import com.niftyautotrader.service.execution.OrderExecutionService;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import com.niftyautotrader.service.sentiment.SentimentVetoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Runs strategies on a schedule, routes signals through sentiment veto and RiskEngine,
 * then hands approved signals to OrderExecutionService.
 */
@Service
public class StrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String NIFTY = "NIFTY";

    private final List<TradingStrategy> strategies;
    private final CandleRepository candleRepo;
    private final SignalRepository signalRepo;
    private final SentimentVetoService sentimentVeto;
    private final OrderExecutionService executionService;
    private final Broker broker;

    public StrategyEngine(List<TradingStrategy> strategies,
                          CandleRepository candleRepo,
                          SignalRepository signalRepo,
                          SentimentVetoService sentimentVeto,
                          OrderExecutionService executionService,
                          Broker broker) {
        this.strategies = strategies;
        this.candleRepo = candleRepo;
        this.signalRepo = signalRepo;
        this.sentimentVeto = sentimentVeto;
        this.executionService = executionService;
        this.broker = broker;
        log.info("StrategyEngine loaded {} strategies: {}",
            strategies.size(),
            strategies.stream().map(TradingStrategy::getName).toList());
    }

    /** Run strategy evaluation every minute during market hours */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runStrategies() {
        MarketContext ctx = buildContext(NIFTY);
        if (ctx == null) return;

        for (TradingStrategy strategy : strategies) {
            if (!strategy.isEnabled()) continue;
            try {
                Optional<Signal> signalOpt = strategy.evaluate(ctx);
                signalOpt.ifPresent(signal -> processSignal(signal, ctx));
            } catch (Exception e) {
                log.error("Strategy {} threw exception: {}", strategy.getName(), e.getMessage(), e);
            }
        }
    }

    private void processSignal(Signal signal, MarketContext ctx) {
        signalRepo.save(signal);
        log.info("Signal generated: strategy={} direction={} symbol={}",
            signal.getStrategyName(), signal.getDirection(), signal.getSymbol());

        // Sentiment veto check — LLM advisory layer, never originates trades
        String vetoReason = sentimentVeto.shouldVeto(signal.getDirection());
        if (vetoReason != null) {
            signal.setVetoedBySentiment(true);
            signal.setVetoReason(vetoReason);
            signalRepo.save(signal);
            log.info("Signal VETOED by sentiment: {} — {}", signal.getDirection(), vetoReason);
            return;
        }

        // Build order request (RiskEngine check happens inside OrderExecutionService)
        if (signal.getDirection() == SignalDirection.NEUTRAL
            || signal.getDirection() == SignalDirection.EXIT) {
            return;
        }

        OrderRequest orderRequest = OrderRequest.builder()
            .symbol(signal.getSymbol())
            .side(OrderSide.BUY)
            .quantity(signal.getLots() * 75) // lot size
            .strategyName(signal.getStrategyName())
            .limitPrice(signal.getSuggestedEntry())
            .stopLossPrice(signal.getSuggestedStopLoss())
            .signalId(signal.getId())
            .closingOrder(false)
            .build();

        executionService.submitOpenOrder(orderRequest, signal);
        signalRepo.save(signal);
    }

    /** Check stop-losses for open positions every minute */
    @Scheduled(cron = "30 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void monitorStopLosses() {
        // Delegate to a position monitor service (simplified here)
        log.debug("Stop-loss monitoring tick");
    }

    /** Force square-off at 15:15 IST */
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void squareOffAll() {
        log.warn("15:15 square-off triggered — flattening all intraday positions");
        broker.cancelAllAndFlatten();
    }

    private MarketContext buildContext(String symbol) {
        ZonedDateTime cutoff = ZonedDateTime.now(IST).minusHours(2);

        List<Candle> c1m  = candleRepo
            .findBySymbolAndTimeframeAndOpenTimeAfterOrderByOpenTimeAsc(symbol, "1m", cutoff);
        List<Candle> c5m  = candleRepo
            .findBySymbolAndTimeframeAndOpenTimeAfterOrderByOpenTimeAsc(symbol, "5m", cutoff.minusHours(4));
        List<Candle> c15m = candleRepo
            .findBySymbolAndTimeframeAndOpenTimeAfterOrderByOpenTimeAsc(symbol, "15m", cutoff.minusHours(6));

        if (c5m.size() < 30) {
            log.debug("Not enough 5m candles ({}) for strategy evaluation", c5m.size());
            return null;
        }

        double[] highs  = c5m.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = c5m.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        double[] closes = c5m.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = c5m.stream().mapToLong(Candle::getVolume).toArray();

        double vwap = IndicatorUtils.vwapLast(highs, lows, closes, vols);
        double atr  = IndicatorUtils.atrLast(highs, lows, closes, 14);
        double adx  = IndicatorUtils.adxLast(highs, lows, closes, 14);

        BigDecimal lastPrice = broker.getLastPrice(symbol);

        return new MarketContext(symbol, ZonedDateTime.now(IST),
            c1m, c5m, c15m,
            lastPrice, BigDecimal.valueOf(vwap), atr, adx, adx > 25);
    }
}
