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
 *  Hammer (bullish reversal at a swing LOW)
 *   – the pattern candle's low is within swingProximity × ATR of the N-bar swing low
 *   – lower wick ≥ minWickRatio × body  (long tail below, body near top)
 *   – upper wick ≤ maxOtherWickRatio × total range  (almost no upper shadow)
 *   – sellers pushed price down hard but buyers brought it back → bullish reversal
 *   → BUY CE
 *
 *  Inverted Hammer (bearish reversal at a swing HIGH)
 *   – the pattern candle's high is within swingProximity × ATR of the N-bar swing high
 *   – upper wick ≥ minWickRatio × body  (long tail above, body near bottom)
 *   – lower wick ≤ maxOtherWickRatio × total range  (almost no lower shadow)
 *   – buyers pushed price up hard but sellers brought it back → bearish reversal
 *   → BUY PE
 *
 * Swing proximity is the key filter: pattern must occur AT the swing extreme
 * (within swingProximity × ATR), not just anywhere in the price range.
 * This matches the manual backtesting approach — ZigZag swing point + hammer shape.
 *
 * Additional guards:
 *   1. Time: 9:45 – 14:30 IST only
 *   2. RSI(14): Hammer RSI < 70 (not overbought), Inverted Hammer RSI > 30 (not oversold)
 *   3. Minimum candle range: ≥ 0.08 × ATR so we're not trading micro-noise
 *   4. Body must be non-doji: ≥ 0.03 × ATR
 *
 * SL = extreme of the pattern candle (low for Hammer, high for Inv-Hammer) − 0.05 × ATR buffer.
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
    private final double rRRatio;           // reward:risk ratio
    private final int    swingLookback;     // bars to look back for swing high/low
    private final double swingProximity;    // pattern candle extreme must be within this × ATR of swing extreme

    public HammerCandlestickStrategy() {
        this(1.5, 0.25, 2.0, 20, 1.0);
    }

    private HammerCandlestickStrategy(double minWickRatio, double maxOtherWickRatio,
                                       double rRRatio, int swingLookback, double swingProximity) {
        this.minWickRatio      = minWickRatio;
        this.maxOtherWickRatio = maxOtherWickRatio;
        this.rRRatio           = rRRatio;
        this.swingLookback     = swingLookback;
        this.swingProximity    = swingProximity;
    }

    @Override public String getName() { return NAME; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of(
            "minWickRatio",      minWickRatio,
            "maxOtherWickRatio", maxOtherWickRatio,
            "rRRatio",           rRRatio,
            "swingLookback",     (double) swingLookback,
            "swingProximity",    swingProximity
        );
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "minWickRatio",      new double[]{1.2, 1.5, 2.0},
            "maxOtherWickRatio", new double[]{0.20, 0.25, 0.35},
            "rRRatio",           new double[]{1.5, 2.0, 2.5},
            "swingProximity",    new double[]{0.5, 1.0, 1.5}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new HammerCandlestickStrategy(
            p.getOrDefault("minWickRatio",      minWickRatio),
            p.getOrDefault("maxOtherWickRatio", maxOtherWickRatio),
            p.getOrDefault("rRRatio",           rRRatio),
            p.getOrDefault("swingLookback",     (double) swingLookback).intValue(),
            p.getOrDefault("swingProximity",    swingProximity)
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

        double rsi = IndicatorUtils.rsiLast(c1, 14);

        // ── Swing high/low over lookback bars (excluding current candle) ──────
        // The pattern candle must sit AT the swing extreme, not in the middle of a range.
        int lookFrom = Math.max(0, n - swingLookback - 1);
        double swingLow  = Double.MAX_VALUE;
        double swingHigh = -Double.MAX_VALUE;
        for (int i = lookFrom; i < n - 1; i++) {
            swingLow  = Math.min(swingLow,  patternCandles.get(i).getLow().doubleValue());
            swingHigh = Math.max(swingHigh, patternCandles.get(i).getHigh().doubleValue());
        }

        // Proximity check: current candle's extreme must be within swingProximity × ATR
        // of the recent swing extreme — this is the ZigZag-at-extreme filter.
        boolean nearSwingLow  = (cL - swingLow)  <= atr * swingProximity;
        boolean nearSwingHigh = (swingHigh - cH) <= atr * swingProximity;

        // ══════════════════════════════════════════════════════════════════════
        //  HAMMER at swing LOW — Bullish reversal → BUY CE
        //  Long lower wick shows sellers failed; price at recent swing low.
        // ══════════════════════════════════════════════════════════════════════
        boolean isHammer = lowerWick >= body * minWickRatio
                        && upperWick <= totalRange * maxOtherWickRatio;

        log.debug("HAMMER-EVAL {} nearLow={} isHammer={} lowerWick={} body={} RSI={}",
            curr.getOpenTime(), nearSwingLow, isHammer,
            String.format("%.1f", lowerWick), String.format("%.1f", body), String.format("%.0f", rsi));

        if (isHammer && nearSwingLow) {
            if (rsi > 70) return Optional.empty(); // already overbought — not a reversal setup
            if (rsi < 10) return Optional.empty(); // free-falling, not a hammer reversal

            double sl   = cL - atr * 0.05;
            double risk = cC - sl;
            if (risk <= 0) return Optional.empty();
            double tgt  = cC + rRRatio * risk;

            String reason = String.format(
                "HAMMER@SwingLow lowerWick=%.1f body=%.1f upperWick=%.1f RSI=%.0f swLow=%.0f proximity=%.1fpts",
                lowerWick, body, upperWick, rsi, swingLow, cL - swingLow);
            log.info("[HAMMER] {} O={} H={} L={} C={} — {}", curr.getOpenTime(), cO, cH, cL, cC, reason);

            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, cC, sl, tgt, reason));
        }

        // ══════════════════════════════════════════════════════════════════════
        //  INVERTED HAMMER at swing HIGH — Bearish reversal → BUY PE
        //  Long upper wick shows buyers failed; price at recent swing high.
        // ══════════════════════════════════════════════════════════════════════
        boolean isInvertedHammer = upperWick >= body * minWickRatio
                                && lowerWick <= totalRange * maxOtherWickRatio;

        log.debug("INV-HAMMER-EVAL {} nearHigh={} isInvHammer={} upperWick={} body={} RSI={}",
            curr.getOpenTime(), nearSwingHigh, isInvertedHammer,
            String.format("%.1f", upperWick), String.format("%.1f", body), String.format("%.0f", rsi));

        if (isInvertedHammer && nearSwingHigh) {
            if (rsi < 30) return Optional.empty(); // already oversold — not a reversal setup
            if (rsi > 90) return Optional.empty(); // momentum still strong, not yet rejecting

            double sl   = cH + atr * 0.05;
            double risk = sl - cC;
            if (risk <= 0) return Optional.empty();
            double tgt  = cC - rRRatio * risk;

            String reason = String.format(
                "INV_HAMMER@SwingHigh upperWick=%.1f body=%.1f lowerWick=%.1f RSI=%.0f swHigh=%.0f proximity=%.1fpts",
                upperWick, body, lowerWick, rsi, swingHigh, swingHigh - cH);
            log.info("[INV_HAMMER] {} O={} H={} L={} C={} — {}", curr.getOpenTime(), cO, cH, cL, cC, reason);

            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, cC, sl, tgt, reason));
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
