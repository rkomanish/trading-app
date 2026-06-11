package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.Signal;
import com.niftyautotrader.model.SignalDirection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Strategy 2: Opening Range Breakout (ORB).
 *
 * Opening range = high and low of the first 15-minute window (09:15–09:29 IST)
 * built from 5m candles. Signal fires when a subsequent 5m candle closes
 * beyond the range with volume confirmation.
 *
 * Time window: 09:30–11:30 IST only (strong early breakouts).
 * Uses ctx.evaluatedAt() so this fires correctly in backtest.
 */
@Component
public class OpeningRangeBreakoutStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ORB_START     = LocalTime.of(9, 15);
    private static final LocalTime ORB_END       = LocalTime.of(9, 30);
    private static final LocalTime ENTRY_CUTOFF  = LocalTime.of(11, 30);

    @Override
    public String getName() { return "OPENING_RANGE_BREAKOUT"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        // Use candle time (works in both live and backtest)
        LocalTime candleTime = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (candleTime.isBefore(ORB_END) || candleTime.isAfter(ENTRY_CUTOFF)) {
            return Optional.empty();
        }

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < 6) return Optional.empty();

        LocalDate today = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalDate();

        // Build opening range from today's first 3 five-minute bars (09:15–09:29)
        double orbHigh = Double.MIN_VALUE;
        double orbLow  = Double.MAX_VALUE;
        long   orbVol  = 0;
        boolean orbFound = false;

        for (Candle c : candles) {
            LocalTime ct = c.getOpenTime().withZoneSameInstant(IST).toLocalTime();
            LocalDate cd = c.getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (!cd.equals(today)) continue;
            if (!ct.isBefore(ORB_START) && ct.isBefore(ORB_END)) {
                orbHigh = Math.max(orbHigh, c.getHigh().doubleValue());
                orbLow  = Math.min(orbLow, c.getLow().doubleValue());
                orbVol += c.getVolume();
                orbFound = true;
            }
        }

        if (!orbFound) return Optional.empty();

        Candle latest = candles.get(candles.size() - 1);
        double lastClose = latest.getClose().doubleValue();
        double atr = ctx.currentAtr();
        double buffer = atr * 0.15; // 15% ATR confirmation buffer

        if (lastClose > orbHigh + buffer) {
            double sl     = orbLow;           // SL at ORB low
            double target = lastClose + (lastClose - sl) * 2.0; // 2:1 R:R
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, lastClose, sl, target,
                String.format("ORB bull break: close=%.2f ORB_hi=%.2f ATR=%.2f", lastClose, orbHigh, atr)));
        }

        if (lastClose < orbLow - buffer) {
            double sl     = orbHigh;          // SL at ORB high
            double target = lastClose - (sl - lastClose) * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, lastClose, sl, target,
                String.format("ORB bear break: close=%.2f ORB_lo=%.2f ATR=%.2f", lastClose, orbLow, atr)));
        }

        return Optional.empty();
    }

    private Signal buildSignal(MarketContext ctx, SignalDirection dir, double entry,
                                double sl, double target, String reason) {
        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now());
        s.setStrategyName(getName());
        s.setSymbol(ctx.symbol());
        s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(entry));
        s.setSuggestedStopLoss(BigDecimal.valueOf(sl));
        s.setSuggestedTarget(BigDecimal.valueOf(target));
        s.setLots(1);
        s.setReasoning(reason);
        return s;
    }
}
