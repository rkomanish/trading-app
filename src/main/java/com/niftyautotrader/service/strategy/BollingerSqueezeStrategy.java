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
import java.util.Map;
import java.util.Optional;

@Component
public class BollingerSqueezeStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 30);
    private static final LocalTime END   = LocalTime.of(13, 0);
    private static final int BB_PERIOD = 20; private static final double BB_MULT = 2.0;
    private static final int SQUEEZE_LB = 20;
    private static final int MIN_CANDLES = BB_PERIOD + SQUEEZE_LB + 10;

    private final double adxMin;
    private final double slAtrCap;
    private final double targetAtrMult;
    private final double volMult;

    public BollingerSqueezeStrategy() { this(15, 1.0, 2.0, 1.2); }

    private BollingerSqueezeStrategy(double adxMin, double slAtrCap, double targetAtrMult, double volMult) {
        this.adxMin = adxMin; this.slAtrCap = slAtrCap;
        this.targetAtrMult = targetAtrMult; this.volMult = volMult;
    }

    @Override public String getName() { return "BOLLINGER_SQUEEZE_BREAKOUT"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("adxMin", adxMin, "slAtrCap", slAtrCap,
            "targetAtrMult", targetAtrMult, "volMult", volMult);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "adxMin",        new double[]{12, 15, 18},
            "slAtrCap",      new double[]{0.8, 1.0, 1.2},
            "targetAtrMult", new double[]{1.8, 2.0, 2.5}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new BollingerSqueezeStrategy(
            p.getOrDefault("adxMin",        adxMin),
            p.getOrDefault("slAtrCap",      slAtrCap),
            p.getOrDefault("targetAtrMult", targetAtrMult),
            p.getOrDefault("volMult",       volMult)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END) || ctx.adx() < adxMin) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();
        int n = closes.length;

        // Detect squeeze: look for a recent tight period, then current expansion/breakout
        // Squeeze = current BB width has expanded vs the minimum of the last SQUEEZE_LB bars
        double minWidthRecent = Double.MAX_VALUE;
        for (int i = n - SQUEEZE_LB; i < n - 3; i++) {
            double[] slice = Arrays.copyOfRange(closes, Math.max(0, i - BB_PERIOD + 1), i + 1);
            if (slice.length < BB_PERIOD) continue;
            var bb = IndicatorUtils.bollingerBandsLast(slice, BB_PERIOD, BB_MULT);
            minWidthRecent = Math.min(minWidthRecent, bb.upper() - bb.lower());
        }

        var bbNow = IndicatorUtils.bollingerBandsLast(closes, BB_PERIOD, BB_MULT);
        // Require that bands are NOW expanding from the recent squeeze (current > min * 1.02)
        if (minWidthRecent == Double.MAX_VALUE) return Optional.empty();
        if (bbNow.upper() - bbNow.lower() < minWidthRecent * 1.02) return Optional.empty();

        long avgVol = 0;
        for (int i = n - SQUEEZE_LB; i < n; i++) avgVol += vols[i];
        avgVol /= SQUEEZE_LB;
        if (vols[n-1] < avgVol * volMult) return Optional.empty();

        double price = closes[n-1], atr = ctx.currentAtr();

        if (price > bbNow.upper() + atr * 0.1) {
            double sl  = Math.max(bbNow.middle(), price - atr * slAtrCap);
            double tgt = price + atr * targetAtrMult;
            if (Math.abs(price - sl) <= 0 || (tgt - price) / Math.abs(price - sl) < 1.5) return Optional.empty();
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("BB squeeze UP close=%.0f>upper=%.0f ADX=%.1f", price, bbNow.upper(), ctx.adx())));
        }
        if (price < bbNow.lower() - atr * 0.1) {
            double sl  = Math.min(bbNow.middle(), price + atr * slAtrCap);
            double tgt = price - atr * targetAtrMult;
            if (Math.abs(price - sl) <= 0 || (price - tgt) / Math.abs(price - sl) < 1.5) return Optional.empty();
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                String.format("BB squeeze DOWN close=%.0f<lower=%.0f ADX=%.1f", price, bbNow.lower(), ctx.adx())));
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
