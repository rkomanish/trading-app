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
public class EmaCrossoverStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime TRADE_START = LocalTime.of(9, 30);
    private static final LocalTime TRADE_END   = LocalTime.of(14, 0);
    private static final int EMA_FAST = 9, EMA_SLOW = 21, RSI_PERIOD = 14, VOL_LOOKBACK = 20;
    private static final int MIN_CANDLES = EMA_SLOW + RSI_PERIOD + VOL_LOOKBACK + 5;

    // Tunable parameters
    private final double adxMin;
    private final double slAtrMult;
    private final double targetAtrMult;
    private final double rsiLo;
    private final double rsiHi;

    public EmaCrossoverStrategy() {
        this(18, 0.8, 1.8, 40, 75);
    }

    private EmaCrossoverStrategy(double adxMin, double slAtrMult, double targetAtrMult,
                                  double rsiLo, double rsiHi) {
        this.adxMin = adxMin; this.slAtrMult = slAtrMult;
        this.targetAtrMult = targetAtrMult; this.rsiLo = rsiLo; this.rsiHi = rsiHi;
    }

    @Override public String getName() { return "EMA_CROSSOVER_9_21"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("adxMin", adxMin, "slAtrMult", slAtrMult,
            "targetAtrMult", targetAtrMult, "rsiLo", rsiLo, "rsiHi", rsiHi);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "adxMin",        new double[]{15, 18, 22},
            "slAtrMult",     new double[]{0.6, 0.8, 1.0},
            "targetAtrMult", new double[]{1.5, 1.8, 2.2}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new EmaCrossoverStrategy(
            p.getOrDefault("adxMin",        adxMin),
            p.getOrDefault("slAtrMult",     slAtrMult),
            p.getOrDefault("targetAtrMult", targetAtrMult),
            p.getOrDefault("rsiLo",         rsiLo),
            p.getOrDefault("rsiHi",         rsiHi)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(TRADE_START) || t.isAfter(TRADE_END)) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();
        if (ctx.adx() < adxMin) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();
        double[] ema9   = IndicatorUtils.ema(closes, EMA_FAST);
        double[] ema21  = IndicatorUtils.ema(closes, EMA_SLOW);

        int last = closes.length - 1, prev = last - 1;
        double rsi = IndicatorUtils.rsiLast(closes, RSI_PERIOD);
        double atr = ctx.currentAtr();
        double price = closes[last];

        long avgVol = 0;
        for (int i = last - VOL_LOOKBACK + 1; i <= last; i++) avgVol += vols[i];
        avgVol /= VOL_LOOKBACK;
        if (vols[last] < avgVol * 0.5) return Optional.empty();

        boolean bullCross = ema9[prev] <= ema21[prev] && ema9[last] > ema21[last];
        boolean bearCross = ema9[prev] >= ema21[prev] && ema9[last] < ema21[last];
        double vwap = ctx.currentVwap().doubleValue();

        if (bullCross && rsi >= rsiLo && rsi <= rsiHi && price > vwap) {
            double sl = price - atr * slAtrMult, tgt = price + atr * targetAtrMult;
            if (!validRR(price, sl, tgt, true)) return Optional.empty();
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("EMA cross bull RSI=%.1f ADX=%.1f sl=%.0f tgt=%.0f", rsi, ctx.adx(), sl, tgt)));
        }
        if (bearCross && rsi >= 25 && rsi <= 60 && price < vwap) {
            double sl = price + atr * slAtrMult, tgt = price - atr * targetAtrMult;
            if (!validRR(price, sl, tgt, false)) return Optional.empty();
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                String.format("EMA cross bear RSI=%.1f ADX=%.1f sl=%.0f tgt=%.0f", rsi, ctx.adx(), sl, tgt)));
        }
        return Optional.empty();
    }

    private boolean validRR(double e, double sl, double tgt, boolean bull) {
        double risk = Math.abs(e - sl), reward = bull ? tgt - e : e - tgt;
        return risk > 0 && reward / risk >= 1.5;
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
