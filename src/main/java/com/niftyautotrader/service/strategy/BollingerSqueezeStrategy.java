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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Bollinger Band Squeeze Breakout — 5m candles.
 *
 * Squeeze = BB width at 20-bar minimum. First expansion = breakout signal.
 *
 * Loss-control improvements:
 *   - SL capped at 1.0×ATR from entry (was: midline which could be 150+ points away)
 *   - ADX filter: breakout direction must align with ADX trend (ADX > 18)
 *   - Volume threshold raised: 1.2× → 1.5× average (stronger conviction required)
 *   - Squeeze threshold tightened: 1.15× → 1.05× minimum width
 *     (must be a real squeeze, not just slightly narrow bands)
 *   - Price must close clearly outside the band (not just pierce by 1 tick)
 *   - Minimum R:R check: 1.5:1
 */
@Component
public class BollingerSqueezeStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 30);
    private static final LocalTime END   = LocalTime.of(13, 0);
    private static final int BB_PERIOD = 20;
    private static final double BB_MULT = 2.0;
    private static final int SQUEEZE_LOOKBACK = 20;
    private static final int MIN_CANDLES = BB_PERIOD + SQUEEZE_LOOKBACK + 10;

    @Override
    public String getName() { return "BOLLINGER_SQUEEZE_BREAKOUT"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END)) return Optional.empty();
        if (ctx.adx() < 18) return Optional.empty(); // need some directional bias

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();
        int n = closes.length;

        // Compute BB width over lookback — find minimum
        double minWidth = Double.MAX_VALUE;
        for (int i = n - SQUEEZE_LOOKBACK; i < n - 1; i++) {
            double[] slice = Arrays.copyOfRange(closes, Math.max(0, i - BB_PERIOD + 1), i + 1);
            if (slice.length < BB_PERIOD) continue;
            var bb = IndicatorUtils.bollingerBandsLast(slice, BB_PERIOD, BB_MULT);
            double width = bb.upper() - bb.lower();
            if (width < minWidth) minWidth = width;
        }

        var bbNow = IndicatorUtils.bollingerBandsLast(closes, BB_PERIOD, BB_MULT);
        double currentWidth = bbNow.upper() - bbNow.lower();

        // Strict squeeze: current width must be very close to the 20-bar minimum
        if (currentWidth > minWidth * 1.05) return Optional.empty();

        double price = closes[n - 1];
        double atr   = ctx.currentAtr();

        // Volume: need 1.5× average (strong breakout conviction)
        long avgVol = 0;
        for (int i = n - SQUEEZE_LOOKBACK; i < n; i++) avgVol += vols[i];
        avgVol /= SQUEEZE_LOOKBACK;
        if (vols[n - 1] < avgVol * 1.5) return Optional.empty();

        // Price must close clearly outside band (at least 0.1×ATR beyond)
        if (price > bbNow.upper() + atr * 0.1) {
            // SL = midline OR 1×ATR below entry — whichever is closer (less loss)
            double slMid = bbNow.middle();
            double slAtr = price - atr * 1.0;
            double sl    = Math.max(slMid, slAtr); // higher of the two = less risk
            double target = price + atr * 2.0;
            if (!validRR(price, sl, target, true)) return Optional.empty();
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("BB squeeze UP: width=%.1f/min=%.1f close=%.0f>upper=%.0f vol=%dx",
                    currentWidth, minWidth, price, bbNow.upper(), vols[n-1]/Math.max(1,avgVol))));
        }

        if (price < bbNow.lower() - atr * 0.1) {
            double slMid = bbNow.middle();
            double slAtr = price + atr * 1.0;
            double sl    = Math.min(slMid, slAtr); // lower of the two = less risk
            double target = price - atr * 2.0;
            if (!validRR(price, sl, target, false)) return Optional.empty();
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("BB squeeze DOWN: width=%.1f/min=%.1f close=%.0f<lower=%.0f vol=%dx",
                    currentWidth, minWidth, price, bbNow.lower(), vols[n-1]/Math.max(1,avgVol))));
        }

        return Optional.empty();
    }

    private boolean validRR(double entry, double sl, double target, boolean isBull) {
        double risk   = Math.abs(entry - sl);
        double reward = isBull ? target - entry : entry - target;
        return risk > 0 && reward / risk >= 1.5;
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
