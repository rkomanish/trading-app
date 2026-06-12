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
import java.util.Map;
import java.util.Optional;

@Component
public class VwapBollingerStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(10, 0);
    private static final LocalTime END   = LocalTime.of(14, 30);
    private static final int BB_PERIOD = 20; private static final double BB_STD = 2.0;
    private static final int RSI_PERIOD = 14;
    private static final int MIN_CANDLES = BB_PERIOD + RSI_PERIOD + 10;

    private final double slAtrMult;
    private final double targetAtrMult;
    private final double adxMax;
    private final double rsiOversold;
    private final double rsiOverbought;

    public VwapBollingerStrategy() { this(1.0, 2.0, 40, 35, 65); }

    private VwapBollingerStrategy(double slAtrMult, double targetAtrMult, double adxMax,
                                   double rsiOversold, double rsiOverbought) {
        this.slAtrMult = slAtrMult; this.targetAtrMult = targetAtrMult;
        this.adxMax = adxMax; this.rsiOversold = rsiOversold; this.rsiOverbought = rsiOverbought;
    }

    @Override public String getName() { return "VWAP_BOLLINGER_REVERSION"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("slAtrMult", slAtrMult, "targetAtrMult", targetAtrMult, "adxMax", adxMax);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "adxMax",        new double[]{35, 40, 45},
            "slAtrMult",     new double[]{0.8, 1.0, 1.2},
            "targetAtrMult", new double[]{1.8, 2.0, 2.5}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new VwapBollingerStrategy(
            p.getOrDefault("slAtrMult",     slAtrMult),
            p.getOrDefault("targetAtrMult", targetAtrMult),
            p.getOrDefault("adxMax",        adxMax),
            rsiOversold, rsiOverbought
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END)) return Optional.empty();
        // ADX gate: allow mean reversion even in moderate-trend markets (up to adxMax)
        // Only block extremely strong trends (e.g. ADX > 50 = runaway trend)
        if (ctx.adx() > Math.max(adxMax, 50)) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] highs  = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();

        var bb = IndicatorUtils.bollingerBandsLast(closes, BB_PERIOD, BB_STD);
        double[] rsiArr = IndicatorUtils.rsi(closes, RSI_PERIOD);
        int last = closes.length - 1;
        double rsiNow = rsiArr[last], rsiPrev = rsiArr[last-1], rsiPrev2 = rsiArr[last-2];
        double vwap = IndicatorUtils.vwapLast(highs, lows, closes, vols);
        double atr = ctx.currentAtr(), price = closes[last];

        // Mean reversion: price at BB extreme + RSI extreme + single reversal bar
        if (price < bb.lower() && rsiNow < rsiOversold && rsiNow > rsiPrev
                && price <= vwap + atr * 0.5) {
            double sl = price - atr * slAtrMult, tgt = price + atr * targetAtrMult;
            if (tgt - price < (price - sl) * 1.5) return Optional.empty(); // enforce 1.5:1 R:R
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("BB lower RSI=%.1f↑ ADX=%.1f", rsiNow, ctx.adx())));
        }
        if (price > bb.upper() && rsiNow > rsiOverbought && rsiNow < rsiPrev
                && price >= vwap - atr * 0.5) {
            double sl = price + atr * slAtrMult, tgt = price - atr * targetAtrMult;
            if (price - tgt < (sl - price) * 1.5) return Optional.empty();
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                String.format("BB upper RSI=%.1f↓ ADX=%.1f", rsiNow, ctx.adx())));
        }
        return Optional.empty();
    }

    private Signal signal(MarketContext ctx, SignalDirection dir, double price,
                           double sl, double target, String reason) {
        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now()); s.setStrategyName(getName());
        s.setSymbol(ctx.symbol()); s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(price));
        s.setSuggestedStopLoss(BigDecimal.valueOf(sl));
        s.setSuggestedTarget(BigDecimal.valueOf(target));
        s.setLots(1); s.setReasoning(reason);
        return s;
    }
}
