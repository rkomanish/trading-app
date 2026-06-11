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
 * VWAP + Bollinger Band mean-reversion — 5m candles.
 *
 * LONG_CE: price at lower BB + RSI oversold turning up + near/below VWAP
 * LONG_PE: price at upper BB + RSI overbought turning down + near/above VWAP
 *
 * Loss-control improvements:
 *   - Require 2 consecutive RSI bars turning (not just 1) — avoids catching
 *     a falling knife that just ticked up once
 *   - SL tightened: 1.5×ATR → 1.0×ATR
 *   - Target reduced: 2.5×ATR → 2.0×ATR (easier to reach, fewer TIME_EXIT losses)
 *   - ADX filter: skip if ADX > 30 (strong trend = mean reversion dangerous)
 *   - BB band touch must be real pierce (price < lower band, not just touching)
 */
@Component
public class VwapBollingerStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START  = LocalTime.of(10, 0);  // avoid volatile open
    private static final LocalTime CUTOFF = LocalTime.of(14, 30);
    private static final int BB_PERIOD = 20;
    private static final double BB_STDDEV = 2.0;
    private static final int RSI_PERIOD = 14;
    private static final int MIN_CANDLES = BB_PERIOD + RSI_PERIOD + 10;

    @Override
    public String getName() { return "VWAP_BOLLINGER_REVERSION"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        LocalTime nowIst = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (nowIst.isBefore(START) || nowIst.isAfter(CUTOFF)) return Optional.empty();

        // Skip during strong trends — mean reversion dangerous in trending markets
        if (ctx.adx() > 30) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] highs  = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();

        var bb     = IndicatorUtils.bollingerBandsLast(closes, BB_PERIOD, BB_STDDEV);
        double[] rsiArr = IndicatorUtils.rsi(closes, RSI_PERIOD);
        int last   = closes.length - 1;
        double rsiNow   = rsiArr[last];
        double rsiPrev  = rsiArr[last - 1];
        double rsiPrev2 = rsiArr[last - 2];

        double vwap  = IndicatorUtils.vwapLast(highs, lows, closes, vols);
        double atr   = ctx.currentAtr();
        double price = closes[last];

        // LONG_CE: real pierce of lower band + RSI turning up for 2 bars
        boolean piercedLower   = price < bb.lower();
        boolean rsiTurningUp2  = rsiNow < 38 && rsiNow > rsiPrev && rsiPrev > rsiPrev2;
        boolean belowVwap      = price <= vwap + atr * 0.2;

        if (piercedLower && rsiTurningUp2 && belowVwap) {
            double sl     = price - atr * 1.0;  // tighter SL
            double target = price + atr * 2.0;  // easier target
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("BB lower pierce %.0f RSI=%.1f↑↑ VWAP=%.0f ADX=%.1f",
                    bb.lower(), rsiNow, vwap, ctx.adx())));
        }

        // LONG_PE: real pierce of upper band + RSI turning down for 2 bars
        boolean piercedUpper    = price > bb.upper();
        boolean rsiTurningDown2 = rsiNow > 62 && rsiNow < rsiPrev && rsiPrev < rsiPrev2;
        boolean aboveVwap       = price >= vwap - atr * 0.2;

        if (piercedUpper && rsiTurningDown2 && aboveVwap) {
            double sl     = price + atr * 1.0;
            double target = price - atr * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("BB upper pierce %.0f RSI=%.1f↓↓ VWAP=%.0f ADX=%.1f",
                    bb.upper(), rsiNow, vwap, ctx.adx())));
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
