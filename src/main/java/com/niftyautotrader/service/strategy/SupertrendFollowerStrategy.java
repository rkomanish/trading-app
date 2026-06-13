package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.Signal;
import com.niftyautotrader.model.SignalDirection;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SupertrendFollowerStrategy implements TunableStrategy {

    private static final int VOL_LOOKBACK = 10;

    private final int stPeriod;
    private final double stMult;
    private final double adxMin;
    private final double slAtrCap;

    public SupertrendFollowerStrategy() { this(7, 2.0, 20, 0.4); }

    private SupertrendFollowerStrategy(int stPeriod, double stMult, double adxMin, double slAtrCap) {
        this.stPeriod = stPeriod; this.stMult = stMult;
        this.adxMin = adxMin; this.slAtrCap = slAtrCap;
    }

    @Override public String getName() { return "SUPERTREND_FOLLOWER"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("stPeriod", (double) stPeriod, "stMult", stMult,
            "adxMin", adxMin, "slAtrCap", slAtrCap);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "adxMin",   new double[]{15, 20, 25},
            "stMult",   new double[]{1.5, 2.0, 2.5},
            "slAtrCap", new double[]{0.3, 0.4, 0.5}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new SupertrendFollowerStrategy(
            (int) Math.round(p.getOrDefault("stPeriod", (double) stPeriod)),
            p.getOrDefault("stMult",   stMult),
            p.getOrDefault("adxMin",   adxMin),
            p.getOrDefault("slAtrCap", slAtrCap)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        List<Candle> candles = ctx.candles15m();
        int minCandles = stPeriod + VOL_LOOKBACK + 10;
        if (candles.size() < minCandles || ctx.adx() < adxMin) return Optional.empty();

        double[] highs  = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();

        var st = IndicatorUtils.supertrend(highs, lows, closes, stPeriod, stMult);
        int last = closes.length - 1, prev = last - 1, prev2 = last - 2;

        // Trend-following entry: price just crossed the supertrend line (within last 2 bars)
        // Bull: price was below ST 2 bars ago, now above it
        // Bear: price was above ST 2 bars ago, now below it
        boolean bullEntry = closes[prev2] <= st.supertrend()[prev2]
            && closes[last] > st.supertrend()[last]
            && st.isBullish()[last];
        boolean bearEntry = closes[prev2] >= st.supertrend()[prev2]
            && closes[last] < st.supertrend()[last]
            && !st.isBullish()[last];
        if (!bullEntry && !bearEntry) return Optional.empty();

        long avgVol = 0;
        for (int i = last - VOL_LOOKBACK + 1; i <= last; i++) avgVol += vols[i];
        avgVol /= VOL_LOOKBACK;
        if (vols[last] < avgVol * 0.8) return Optional.empty();

        double stLine = st.supertrend()[last];
        double price  = closes[last];
        double atr    = ctx.currentAtr();
        if (Math.abs(price - stLine) > atr * 2.0) return Optional.empty();

        if (bullEntry) {
            double sl  = Math.max(stLine - atr * 0.2, price - atr * slAtrCap);
            double tgt = price + (price - sl) * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("ST bull cross ST=%.0f ADX=%.1f sl=%.0f tgt=%.0f", stLine, ctx.adx(), sl, tgt)));
        }
        double sl  = Math.min(stLine + atr * 0.2, price + atr * slAtrCap);
        double tgt = price - (sl - price) * 2.0;
        return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
            String.format("ST bear cross ST=%.0f ADX=%.1f sl=%.0f tgt=%.0f", stLine, ctx.adx(), sl, tgt)));
    }

    private Signal buildSignal(MarketContext ctx, SignalDirection dir, double entry,
                                double sl, double target, String reason) {
        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now()); s.setStrategyName(getName());
        s.setSymbol(ctx.symbol()); s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(entry));
        s.setSuggestedStopLoss(BigDecimal.valueOf(sl));
        s.setSuggestedTarget(BigDecimal.valueOf(target));
        s.setLots(1); s.setReasoning(reason);
        return s;
    }
}
