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
public class EmaPullbackStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 30);
    private static final LocalTime END   = LocalTime.of(13, 0);
    private static final int EMA_FAST = 9, EMA_SLOW = 21, RSI_PERIOD = 14;
    private static final int MIN_CANDLES = EMA_SLOW + RSI_PERIOD + 5;

    private final double adxMin;
    private final double slAtrMult;
    private final double targetAtrMult;
    private final double touchTol;

    public EmaPullbackStrategy() { this(20, 0.8, 2.0, 0.003); }

    private EmaPullbackStrategy(double adxMin, double slAtrMult, double targetAtrMult, double touchTol) {
        this.adxMin = adxMin; this.slAtrMult = slAtrMult;
        this.targetAtrMult = targetAtrMult; this.touchTol = touchTol;
    }

    @Override public String getName() { return "EMA21_PULLBACK"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("adxMin", adxMin, "slAtrMult", slAtrMult,
            "targetAtrMult", targetAtrMult, "touchTol", touchTol);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "adxMin",        new double[]{18, 20, 22},
            "slAtrMult",     new double[]{0.6, 0.8, 1.0},
            "targetAtrMult", new double[]{1.5, 2.0, 2.5}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new EmaPullbackStrategy(
            p.getOrDefault("adxMin",        adxMin),
            p.getOrDefault("slAtrMult",     slAtrMult),
            p.getOrDefault("targetAtrMult", targetAtrMult),
            p.getOrDefault("touchTol",      touchTol)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END) || ctx.adx() < adxMin) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] ema9   = IndicatorUtils.ema(closes, EMA_FAST);
        double[] ema21  = IndicatorUtils.ema(closes, EMA_SLOW);
        double rsi = IndicatorUtils.rsiLast(closes, RSI_PERIOD);

        int last = closes.length - 1;
        double price = closes[last], e9 = ema9[last], e21 = ema21[last], atr = ctx.currentAtr();

        if (Math.abs(price - e21) / e21 >= touchTol) return Optional.empty();

        // Require price to have dipped below EMA21 recently then recovered (pullback confirmation)
        boolean recentDipBull = last >= 2 && closes[last - 1] < ema21[last - 1] && price > e21;
        boolean recentRipBear = last >= 2 && closes[last - 1] > ema21[last - 1] && price < e21;

        if (e9 > e21 && rsi >= 38 && rsi <= 62 && recentDipBull) {
            double sl = e21 - atr * slAtrMult, tgt = price + atr * targetAtrMult;
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("EMA21 pullback bull price=%.0f e21=%.0f RSI=%.1f ADX=%.1f",
                    price, e21, rsi, ctx.adx())));
        }
        if (e9 < e21 && rsi >= 38 && rsi <= 62 && recentRipBear) {
            double sl = e21 + atr * slAtrMult, tgt = price - atr * targetAtrMult;
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                String.format("EMA21 pullback bear price=%.0f e21=%.0f RSI=%.1f ADX=%.1f",
                    price, e21, rsi, ctx.adx())));
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
