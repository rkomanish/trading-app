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
public class MACDCrossoverStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 45);
    private static final LocalTime END   = LocalTime.of(13, 30);
    private static final int FAST = 12, SLOW = 26, SIGNAL_P = 9, RSI_PERIOD = 14, VOL_LB = 20;
    private static final int MIN_CANDLES = SLOW + SIGNAL_P + RSI_PERIOD + VOL_LB + 5;

    private final double adxMin;
    private final double slAtrMult;
    private final double targetAtrMult;

    public MACDCrossoverStrategy() { this(22, 0.8, 1.8); }

    private MACDCrossoverStrategy(double adxMin, double slAtrMult, double targetAtrMult) {
        this.adxMin = adxMin; this.slAtrMult = slAtrMult; this.targetAtrMult = targetAtrMult;
    }

    @Override public String getName() { return "MACD_CROSSOVER"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("adxMin", adxMin, "slAtrMult", slAtrMult, "targetAtrMult", targetAtrMult);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "adxMin",        new double[]{18, 22, 25},
            "slAtrMult",     new double[]{0.6, 0.8, 1.0},
            "targetAtrMult", new double[]{1.5, 1.8, 2.2}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new MACDCrossoverStrategy(
            p.getOrDefault("adxMin", adxMin),
            p.getOrDefault("slAtrMult", slAtrMult),
            p.getOrDefault("targetAtrMult", targetAtrMult)
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

        long avgVol = 0;
        for (int i = n - VOL_LB; i < n; i++) avgVol += vols[i];
        avgVol /= VOL_LB;
        if (vols[n-1] < avgVol * 0.9) return Optional.empty();

        double[] emaF = IndicatorUtils.ema(closes, FAST);
        double[] emaS = IndicatorUtils.ema(closes, SLOW);
        double[] macd = new double[n];
        for (int i = 0; i < n; i++) macd[i] = emaF[i] - emaS[i];
        double[] sig  = IndicatorUtils.ema(macd, SIGNAL_P);

        int last = n-1, prev = n-2;
        double histNow  = macd[last] - sig[last];
        double histPrev = macd[prev] - sig[prev];
        boolean bullFlip = histPrev <= 0 && histNow > 0 && Math.abs(histNow) > Math.abs(histPrev);
        boolean bearFlip = histPrev >= 0 && histNow < 0 && Math.abs(histNow) > Math.abs(histPrev);

        double price = closes[last], vwap = ctx.currentVwap().doubleValue();
        double atr = ctx.currentAtr(), rsi = IndicatorUtils.rsiLast(closes, RSI_PERIOD);

        if (bullFlip && price > vwap && rsi > 50) {
            double sl = price - atr * slAtrMult, tgt = price + atr * targetAtrMult;
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("MACD bull hist=%.1f RSI=%.1f ADX=%.1f", histNow, rsi, ctx.adx())));
        }
        if (bearFlip && price < vwap && rsi < 50) {
            double sl = price + atr * slAtrMult, tgt = price - atr * targetAtrMult;
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                String.format("MACD bear hist=%.1f RSI=%.1f ADX=%.1f", histNow, rsi, ctx.adx())));
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
