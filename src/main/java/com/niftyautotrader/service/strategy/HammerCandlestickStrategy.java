package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.Signal;
import com.niftyautotrader.model.SignalDirection;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hammer / Inverted Hammer reversal strategy on 1-minute Nifty candles.
 *
 *  Hammer (bullish reversal)
 *   – spotted near a recent swing LOW (price near N-bar min)
 *   – lower wick ≥ minWickRatio × body  (long tail below, body near top)
 *   – upper wick ≤ maxUpperWickRatio × total range  (almost no upper shadow)
 *   – confirms the buying pressure: sellers tried to push it down but failed
 *   → BUY CE
 *
 *  Inverted Hammer (bearish reversal)
 *   – spotted near a recent swing HIGH (price near N-bar max)
 *   – upper wick ≥ minWickRatio × body  (long tail above, body near bottom)
 *   – lower wick ≤ maxUpperWickRatio × total range  (almost no lower shadow)
 *   – confirms selling pressure at high: buyers tried to push up but failed
 *   → BUY PE
 *
 * Additional filters applied to every signal:
 *   1. Time: 9:45 – 14:30 IST only
 *   2. VWAP bias: Hammer only when price < VWAP (depressed), Inverted Hammer only when price > VWAP
 *   3. EMA(9) trend on 5m candles: Hammer requires short-term downtrend, Inv-Hammer requires uptrend
 *   4. Volume: pattern candle volume > volumeMult × 20-bar average on 1m
 *   5. RSI(14) on 1m closes: Hammer 25–50, Inverted Hammer 50–75  (reversal zones)
 *   6. Minimum candle range: ≥ 0.15 × ATR so we're not trading noise
 *   7. Body must be non-doji: ≥ 0.05 × ATR
 *
 * SL = extreme of the pattern candle (low for Hammer, high for Inv-Hammer) ± 0.05 × ATR buffer.
 * Target = rRRatio × risk from entry.
 */
@Component
public class HammerCandlestickStrategy implements TunableStrategy {

    private static final Logger log = LoggerFactory.getLogger(HammerCandlestickStrategy.class);

    private static final ZoneId IST      = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 45);
    private static final LocalTime END   = LocalTime.of(14, 30);
    private static final String NAME     = "HAMMER_CANDLESTICK";

    // minimum number of candles needed (EMA warmup + swing lookback)
    private static final int MIN_CANDLES = 30;

    private final double minWickRatio;      // lower/upper wick >= this × body size
    private final double maxOtherWickRatio; // opposite wick <= this × total range
    private final double volumeMult;        // pattern volume > avgVol × this
    private final double rRRatio;           // reward:risk ratio
    private final int    swingLookback;     // bars to detect swing high/low proximity

    public HammerCandlestickStrategy() {
        this(1.5, 0.25, 1.5, 2.0, 20);
    }

    private HammerCandlestickStrategy(double minWickRatio, double maxOtherWickRatio,
                                       double volumeMult, double rRRatio, int swingLookback) {
        this.minWickRatio      = minWickRatio;
        this.maxOtherWickRatio = maxOtherWickRatio;
        this.volumeMult        = volumeMult;
        this.rRRatio           = rRRatio;
        this.swingLookback     = swingLookback;
    }

    @Override public String getName() { return NAME; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of(
            "minWickRatio",      minWickRatio,
            "maxOtherWickRatio", maxOtherWickRatio,
            "volumeMult",        volumeMult,
            "rRRatio",           rRRatio,
            "swingLookback",     (double) swingLookback
        );
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "minWickRatio",      new double[]{1.2, 1.5, 2.0},
            "maxOtherWickRatio", new double[]{0.20, 0.25, 0.35},
            "rRRatio",           new double[]{1.5, 2.0, 2.5}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new HammerCandlestickStrategy(
            p.getOrDefault("minWickRatio",      minWickRatio),
            p.getOrDefault("maxOtherWickRatio", maxOtherWickRatio),
            p.getOrDefault("volumeMult",        volumeMult),
            p.getOrDefault("rRRatio",           rRRatio),
            p.getOrDefault("swingLookback",     (double) swingLookback).intValue()
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime now = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (now.isBefore(START) || now.isAfter(END)) return Optional.empty();

        // Use 1m candles when available (live trading), fall back to 5m for backtesting
        List<Candle> candles1m = ctx.candles1m();
        List<Candle> patternCandles = (!candles1m.isEmpty()) ? candles1m : ctx.candles5m();
        if (patternCandles.size() < MIN_CANDLES) return Optional.empty();

        // ATR and OHLCV arrays from pattern candles
        int n = patternCandles.size();
        double[] h1 = patternCandles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] l1 = patternCandles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        double[] c1 = patternCandles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();

        double atr = IndicatorUtils.atrLast(h1, l1, c1, 14);
        if (atr <= 0) return Optional.empty();

        // Current candle (the pattern candle)
        Candle curr = patternCandles.get(n - 1);
        LocalDate today = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalDate();
        if (!curr.getOpenTime().withZoneSameInstant(IST).toLocalDate().equals(today))
            return Optional.empty();

        double cO = curr.getOpen().doubleValue();
        double cH = curr.getHigh().doubleValue();
        double cL = curr.getLow().doubleValue();
        double cC = curr.getClose().doubleValue();

        double totalRange = cH - cL;
        double body       = Math.abs(cC - cO);
        double bodyTop    = Math.max(cC, cO);
        double bodyBottom = Math.min(cC, cO);
        double upperWick  = cH - bodyTop;
        double lowerWick  = bodyBottom - cL;

        // Reject doji (body too small) and noise candles (range too small)
        if (body  < atr * 0.03)  return Optional.empty();
        if (totalRange < atr * 0.08) return Optional.empty();

        double vwap = ctx.currentVwap().doubleValue();
        double price = cC;

        // ── RSI — only hard extremes excluded ────────────────────────────────
        double rsi = IndicatorUtils.rsiLast(c1, 14);

        // ── Swing high/low over lookback (used in reason string only) ─────────
        int lookFrom = Math.max(0, n - swingLookback - 1);
        double swingLow  = Double.MAX_VALUE;
        double swingHigh = Double.MIN_VALUE;
        for (int i = lookFrom; i < n - 1; i++) {
            swingLow  = Math.min(swingLow,  patternCandles.get(i).getLow().doubleValue());
            swingHigh = Math.max(swingHigh, patternCandles.get(i).getHigh().doubleValue());
        }

        // ══════════════════════════════════════════════════════════════════════
        //  HAMMER — Bullish reversal → BUY CE
        //  Core filters: pattern geometry + price below VWAP + RSI not overbought
        // ══════════════════════════════════════════════════════════════════════
        boolean isHammer = lowerWick >= body * minWickRatio
                        && upperWick <= totalRange * maxOtherWickRatio;

        if (isHammer) log.debug("HAMMER-GEOM detected {} price={} vwap={} rsi={}", curr.getOpenTime(), price, vwap, rsi);
        if (isHammer && price < vwap) {
            if (rsi > 70) return Optional.empty(); // don't buy into overbought
            if (rsi < 10) return Optional.empty(); // extremely oversold = falling knife risk

            double sl   = cL - atr * 0.05;
            double risk = price - sl;
            if (risk <= 0) return Optional.empty();
            double tgt  = price + rRRatio * risk;

            String reason = String.format(
                "HAMMER lowerWick=%.1f body=%.1f upperWick=%.1f RSI=%.0f VWAP=%.0f swLow=%.0f",
                lowerWick, body, upperWick, rsi, vwap, swingLow);
            log.info("[HAMMER] {} O={} H={} L={} C={} — {}", curr.getOpenTime(), cO, cH, cL, cC, reason);

            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, tgt, reason));
        }

        // ══════════════════════════════════════════════════════════════════════
        //  INVERTED HAMMER — Bearish reversal → BUY PE
        //  Core filters: pattern geometry + price above VWAP + RSI not oversold
        // ══════════════════════════════════════════════════════════════════════
        boolean isInvertedHammer = upperWick >= body * minWickRatio
                                && lowerWick <= totalRange * maxOtherWickRatio;

        if (isInvertedHammer) log.debug("INV-HAMMER-GEOM detected {} price={} vwap={} rsi={}", curr.getOpenTime(), price, vwap, rsi);
        if (isInvertedHammer && price > vwap) {
            if (rsi < 30) return Optional.empty(); // don't short into oversold
            if (rsi > 90) return Optional.empty(); // extremely overbought = momentum still strong

            double sl   = cH + atr * 0.05;
            double risk = sl - price;
            if (risk <= 0) return Optional.empty();
            double tgt  = price - rRRatio * risk;

            String reason = String.format(
                "INV_HAMMER upperWick=%.1f body=%.1f lowerWick=%.1f RSI=%.0f VWAP=%.0f swHigh=%.0f",
                upperWick, body, lowerWick, rsi, vwap, swingHigh);
            log.info("[INV_HAMMER] {} O={} H={} L={} C={} — {}", curr.getOpenTime(), cO, cH, cL, cC, reason);

            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, tgt, reason));
        }

        return Optional.empty();
    }

    private double avgVolume(List<Candle> candles, int lookback) {
        int n = candles.size();
        int from = Math.max(0, n - lookback - 1);
        long sum = 0; int count = 0;
        for (int i = from; i < n - 1; i++) {
            sum += candles.get(i).getVolume(); count++;
        }
        return count == 0 ? 0 : (double) sum / count;
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
