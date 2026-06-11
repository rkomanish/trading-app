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
 * Strategy 7: Bollinger Band Squeeze Breakout on 5m candles.
 *
 * A "squeeze" occurs when BB width (upper - lower) shrinks to its lowest
 * in the last 20 bars — indicating low volatility / consolidation.
 * The first expansion (band widening) after a squeeze is the breakout signal.
 *
 * LONG_CE when:
 *   - BB squeeze confirmed (width at 20-bar low)
 *   - Current bar closes ABOVE upper BB (upside breakout)
 *   - Volume above 20-bar average (conviction)
 *
 * LONG_PE when:
 *   - BB squeeze confirmed
 *   - Current bar closes BELOW lower BB (downside breakout)
 *   - Volume above 20-bar average
 *
 * SL: midline (BB middle = 20-period SMA); Target: 2.5× ATR from entry.
 * Time: 09:30 – 13:00 IST (avoid late-day low volume squeezes).
 */
@Component
public class BollingerSqueezeStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 30);
    private static final LocalTime END   = LocalTime.of(13, 0);
    private static final int BB_PERIOD = 20;
    private static final double BB_MULT = 2.0;
    private static final int SQUEEZE_LOOKBACK = 20;
    private static final int MIN_CANDLES = BB_PERIOD + SQUEEZE_LOOKBACK + 5;

    @Override
    public String getName() { return "BOLLINGER_SQUEEZE_BREAKOUT"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END)) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();
        int n = closes.length;

        // Compute BB width for all bars in the lookback window
        double minWidth = Double.MAX_VALUE;
        double maxWidth = Double.MIN_VALUE;
        for (int i = n - SQUEEZE_LOOKBACK; i < n - 1; i++) {
            double[] slice = java.util.Arrays.copyOfRange(closes, Math.max(0, i - BB_PERIOD + 1), i + 1);
            if (slice.length < BB_PERIOD) continue;
            var bb = IndicatorUtils.bollingerBandsLast(slice, BB_PERIOD, BB_MULT);
            double width = bb.upper() - bb.lower();
            if (width < minWidth) minWidth = width;
            if (width > maxWidth) maxWidth = width;
        }

        var bbNow = IndicatorUtils.bollingerBandsLast(closes, BB_PERIOD, BB_MULT);
        double currentWidth = bbNow.upper() - bbNow.lower();

        // Squeeze: current width at or near historical min
        boolean squeezeActive = currentWidth <= minWidth * 1.15;
        if (!squeezeActive) return Optional.empty();

        double price = closes[n - 1];
        double atr   = ctx.currentAtr();

        // Volume confirmation: current bar volume above 20-bar average
        long avgVol = 0;
        for (int i = n - SQUEEZE_LOOKBACK; i < n; i++) avgVol += vols[i];
        avgVol /= SQUEEZE_LOOKBACK;
        boolean highVolume = vols[n - 1] > avgVol * 1.2;

        if (price > bbNow.upper() && highVolume) {
            double sl     = bbNow.middle(); // midline as SL
            double target = price + atr * 2.5;
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("BB squeeze breakout UP: width=%.1f min=%.1f vol=%dk>avg%dk",
                    currentWidth, minWidth, vols[n-1]/1000, avgVol/1000)));
        }

        if (price < bbNow.lower() && highVolume) {
            double sl     = bbNow.middle();
            double target = price - atr * 2.5;
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("BB squeeze breakout DOWN: width=%.1f min=%.1f vol=%dk>avg%dk",
                    currentWidth, minWidth, vols[n-1]/1000, avgVol/1000)));
        }

        return Optional.empty();
    }

    private Signal signal(MarketContext ctx, SignalDirection dir, double price,
                           double sl, double target, String reason) {
        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now());
        s.setStrategyName(getName());
        s.setSymbol(ctx.symbol());
        s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(price));
        s.setSuggestedStopLoss(BigDecimal.valueOf(sl));
        s.setSuggestedTarget(BigDecimal.valueOf(target));
        s.setLots(1);
        s.setReasoning(reason);
        return s;
    }
}
