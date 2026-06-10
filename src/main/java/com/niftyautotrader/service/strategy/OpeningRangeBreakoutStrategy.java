package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.Signal;
import com.niftyautotrader.model.SignalDirection;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Strategy 2: Opening Range Breakout (ORB).
 *
 * Opening range = high and low of the first 15 minutes (09:15–09:30 IST).
 * Signal triggers when price breaks above (CE) or below (PE) the opening range
 * with ATR-based stop-loss and 2:1 target.
 *
 * Conditions:
 *   - Time is 09:30–11:00 IST (only early breakouts)
 *   - Price closes above ORB high + buffer → LONG_CE
 *   - Price closes below ORB low - buffer → LONG_PE
 *   - ATR provides stop-loss width
 */
@Component
public class OpeningRangeBreakoutStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ORB_START = LocalTime.of(9, 15);
    private static final LocalTime ORB_END   = LocalTime.of(9, 30);
    private static final LocalTime ENTRY_CUTOFF = LocalTime.of(11, 0);

    @Override
    public String getName() { return "OPENING_RANGE_BREAKOUT"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime nowIst = ZonedDateTime.now(IST).toLocalTime();
        if (nowIst.isBefore(ORB_END) || nowIst.isAfter(ENTRY_CUTOFF)) {
            return Optional.empty();
        }

        List<Candle> candles1m = ctx.candles1m();
        if (candles1m.size() < 20) return Optional.empty();

        // Identify opening range candles (09:15–09:29)
        double orbHigh = Double.MIN_VALUE;
        double orbLow  = Double.MAX_VALUE;
        boolean orbFound = false;

        for (Candle c : candles1m) {
            LocalTime ct = c.getOpenTime().withZoneSameInstant(IST).toLocalTime();
            if (!ct.isBefore(ORB_START) && ct.isBefore(ORB_END)) {
                orbHigh = Math.max(orbHigh, c.getHigh().doubleValue());
                orbLow  = Math.min(orbLow, c.getLow().doubleValue());
                orbFound = true;
            }
        }

        if (!orbFound || orbHigh == Double.MIN_VALUE) return Optional.empty();

        double lastClose = candles1m.get(candles1m.size() - 1).getClose().doubleValue();
        double atr = ctx.currentAtr();
        double buffer = atr * 0.1; // 10% ATR as breakout buffer

        if (lastClose > orbHigh + buffer) {
            double sl = orbHigh - atr * 1.5;
            double target = lastClose + (lastClose - sl) * 2.0;
            String reason = String.format("ORB bullish break: close(%.2f) > ORB_high(%.2f), ATR=%.2f",
                lastClose, orbHigh, atr);
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, lastClose, sl, target, reason));
        }

        if (lastClose < orbLow - buffer) {
            double sl = orbLow + atr * 1.5;
            double target = lastClose - (sl - lastClose) * 2.0;
            String reason = String.format("ORB bearish break: close(%.2f) < ORB_low(%.2f), ATR=%.2f",
                lastClose, orbLow, atr);
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, lastClose, sl, target, reason));
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
