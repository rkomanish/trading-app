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
 * Opening Range Breakout (ORB) — 5m candles.
 *
 * Opening range = high/low of the first 15 min (09:15–09:29 IST).
 * Signal fires on a 5m close beyond the range with volume confirmation.
 *
 * Loss-control improvements:
 *   - SL capped at 1.0×ATR from entry even if ORB range is wider
 *     (prevents a wide ORB day from creating a ₹10k+ loss)
 *   - ORB range must be ≤ 2×ATR — if range is too wide (very volatile open),
 *     skip the trade entirely (risk/reward not worth it)
 *   - Confirmation buffer tightened to 0.1×ATR (was 0.15×ATR)
 *   - Volume on breakout candle must be > 1.5× opening-range average
 *   - Entry window narrowed: 09:30–11:00 (was 11:30) — only strong early breakouts
 */
@Component
public class OpeningRangeBreakoutStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ORB_START    = LocalTime.of(9, 15);
    private static final LocalTime ORB_END      = LocalTime.of(9, 30);
    private static final LocalTime ENTRY_CUTOFF = LocalTime.of(11, 0);
    private static final double MAX_ORB_ATR_MULT = 2.0; // skip if ORB range > 2×ATR
    private static final double SL_ATR_CAP       = 1.0; // max SL distance = 1×ATR

    @Override
    public String getName() { return "OPENING_RANGE_BREAKOUT"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime candleTime = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (candleTime.isBefore(ORB_END) || candleTime.isAfter(ENTRY_CUTOFF)) {
            return Optional.empty();
        }

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < 6) return Optional.empty();

        LocalDate today = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalDate();
        double atr = ctx.currentAtr();

        // Build opening range from today's first 3 five-minute bars (09:15–09:29)
        double orbHigh = Double.MIN_VALUE, orbLow = Double.MAX_VALUE;
        long orbVol = 0;
        int orbBars = 0;
        for (Candle c : candles) {
            LocalTime ct = c.getOpenTime().withZoneSameInstant(IST).toLocalTime();
            LocalDate cd = c.getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (!cd.equals(today)) continue;
            if (!ct.isBefore(ORB_START) && ct.isBefore(ORB_END)) {
                orbHigh = Math.max(orbHigh, c.getHigh().doubleValue());
                orbLow  = Math.min(orbLow,  c.getLow().doubleValue());
                orbVol += c.getVolume();
                orbBars++;
            }
        }
        if (orbBars == 0 || orbHigh == Double.MIN_VALUE) return Optional.empty();

        // Skip if ORB range is too wide (volatile open → unpredictable)
        double orbRange = orbHigh - orbLow;
        if (orbRange > atr * MAX_ORB_ATR_MULT) return Optional.empty();

        Candle latest   = candles.get(candles.size() - 1);
        double price    = latest.getClose().doubleValue();
        double vol      = latest.getVolume();
        double avgOrbVol = orbBars > 0 ? (double) orbVol / orbBars : 0;
        double buffer   = atr * 0.10;

        // Volume confirmation: breakout candle must have 1.5× ORB-period average volume
        if (avgOrbVol > 0 && vol < avgOrbVol * 1.5) return Optional.empty();

        if (price > orbHigh + buffer) {
            // SL = ORB low, but capped at 1×ATR below entry to limit max loss
            double rawSl  = orbLow;
            double sl     = Math.max(rawSl, price - atr * SL_ATR_CAP);
            double target = price + (price - sl) * 2.0; // 2:1 R:R from actual SL
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("ORB bull: close=%.0f ORB_hi=%.0f range=%.0f ATR=%.0f vol=%dk",
                    price, orbHigh, orbRange, atr, (long)vol/1000)));
        }

        if (price < orbLow - buffer) {
            double rawSl  = orbHigh;
            double sl     = Math.min(rawSl, price + atr * SL_ATR_CAP);
            double target = price - (sl - price) * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("ORB bear: close=%.0f ORB_lo=%.0f range=%.0f ATR=%.0f vol=%dk",
                    price, orbLow, orbRange, atr, (long)vol/1000)));
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
