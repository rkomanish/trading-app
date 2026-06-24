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
        this(2.0, 0.15, 1.5, 2.0, 20);
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
            "minWickRatio",      new double[]{1.5, 2.0, 2.5},
            "maxOtherWickRatio", new double[]{0.10, 0.15, 0.20},
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
        if (body  < atr * 0.05)  return Optional.empty();
        if (totalRange < atr * 0.15) return Optional.empty();

        double vwap = ctx.currentVwap().doubleValue();
        double price = cC;

        // ── EMA(21) trend over last 20 bars — use 5m if available, else pattern candles ──
        // Use 20-bar lookback (not 10) — on 1m that's 20 min, on 5m it's 100 min (cleaner)
        List<Candle> candles5m = ctx.candles5m();
        List<Candle> trendCandles = (candles5m.size() >= 25) ? candles5m : patternCandles;
        boolean downtrend = false, uptrend = false;
        if (trendCandles.size() >= 25) {
            double[] ct = trendCandles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
            double[] ema21 = IndicatorUtils.ema(ct, 21);
            int m = ema21.length;
            downtrend = ema21[m - 1] < ema21[m - 20];
            uptrend   = ema21[m - 1] > ema21[m - 20];
        } else {
            // Not enough candles for trend filter — allow both directions
            downtrend = true;
            uptrend   = true;
        }

        // ── Volume and RSI from pattern candles ───────────────────────────────
        double avgVol = avgVolume(patternCandles, 20);
        double rsi = IndicatorUtils.rsiLast(c1, 14);

        // ── Swing proximity (near N-bar low/high) ─────────────────────────────
        int lookFrom = Math.max(0, n - swingLookback - 1);
        double swingLow  = Double.MAX_VALUE;
        double swingHigh = Double.MIN_VALUE;
        for (int i = lookFrom; i < n - 1; i++) {
            swingLow  = Math.min(swingLow,  patternCandles.get(i).getLow().doubleValue());
            swingHigh = Math.max(swingHigh, patternCandles.get(i).getHigh().doubleValue());
        }
        double proximityBand = atr * 1.5; // within 1.5×ATR of swing extreme (wider for 1m noise)
        boolean nearSwingLow  = cL <= swingLow  + proximityBand;
        boolean nearSwingHigh = cH >= swingHigh - proximityBand;

        // ══════════════════════════════════════════════════════════════════════
        //  HAMMER — Bullish reversal → BUY CE
        // ══════════════════════════════════════════════════════════════════════
        boolean isHammer = lowerWick >= body * minWickRatio            // long lower wick
                        && upperWick <= totalRange * maxOtherWickRatio; // tiny upper wick

        if (isHammer && nearSwingLow && price < vwap && downtrend) {
            // Volume surge
            if (avgVol > 0 && curr.getVolume() < avgVol * volumeMult) return Optional.empty();
            // RSI: must be in weakened-to-neutral zone — not overbought (>65 would be buying into strength)
            if (rsi < 15 || rsi > 65) return Optional.empty();

            double sl    = cL - atr * 0.05;
            double risk  = price - sl;
            double tgt   = price + rRRatio * risk;

            String reason = String.format(
                "HAMMER nearSwingLow=%.0f lowerWick=%.1f body=%.1f upperWick=%.1f "
                + "vol=%.1fx RSI=%.0f VWAP=%.0f EMA21↓",
                swingLow, lowerWick, body, upperWick,
                avgVol > 0 ? curr.getVolume() / avgVol : 0, rsi, vwap);
            log.info("[HAMMER] {} O={} H={} L={} C={} — {}", curr.getOpenTime(), cO, cH, cL, cC, reason);

            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, tgt, reason));
        }

        // ══════════════════════════════════════════════════════════════════════
        //  INVERTED HAMMER — Bearish reversal → BUY PE
        // ══════════════════════════════════════════════════════════════════════
        boolean isInvertedHammer = upperWick >= body * minWickRatio            // long upper wick
                                && lowerWick <= totalRange * maxOtherWickRatio; // tiny lower wick

        if (isInvertedHammer && nearSwingHigh && price > vwap && uptrend) {
            if (avgVol > 0 && curr.getVolume() < avgVol * volumeMult) return Optional.empty();
            // RSI: must be in strengthened-to-neutral zone — not oversold (<35 would be shorting weakness)
            if (rsi < 35 || rsi > 85) return Optional.empty();

            double sl   = cH + atr * 0.05;
            double risk = sl - price;
            double tgt  = price - rRRatio * risk;

            String reason = String.format(
                "INV_HAMMER nearSwingHigh=%.0f upperWick=%.1f body=%.1f lowerWick=%.1f "
                + "vol=%.1fx RSI=%.0f VWAP=%.0f EMA21↑",
                swingHigh, upperWick, body, lowerWick,
                avgVol > 0 ? curr.getVolume() / avgVol : 0, rsi, vwap);
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
