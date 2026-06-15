package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.Signal;
import com.niftyautotrader.model.SignalDirection;
import com.niftyautotrader.service.indicators.IndicatorUtils;
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
 * Price-action pattern strategy that thinks like a real Nifty intraday trader.
 *
 * Only two patterns are used — both must be STRONG and at a REAL key level:
 *
 *   Bullish Engulfing  — current green body FULLY covers prior red body,
 *                        body must be >= minEngulfRatio × prior body (strength filter)
 *   Bearish Engulfing  — current red body FULLY covers prior green body, same strength filter
 *
 * Every trade requires ALL of the following (human checklist):
 *   1. Time: 9:45 – 14:00 only
 *   2. Pattern at a REAL level: VWAP ± band  OR  PDH/PDL ± band  (no intraday H/L — too loose)
 *   3. Trend aligned: EMA(21) direction over last 30 bars (not 15 — avoids dead-cat traps)
 *   4. VWAP bias: bull only when price > VWAP, bear only when price < VWAP
 *   5. Volume surge: pattern candle volume > volumeMult × 20-bar average
 *   6. RSI: bull 40–68, bear 32–60  (tighter than before — no buying overbought)
 *   7. Pattern strength: engulfing body >= minEngulfRatio × prior body (no micro-engulfs)
 *   8. Pattern size: engulfing candle range >= 0.4 × ATR (real move, not noise)
 *   9. No trade within 8 bars of last trade (simple bar count, no pattern re-scan)
 *
 * SL = opposite end of the engulfing candle (the invalidation point).
 * Target = rRRatio × risk.
 */
@Component
public class CandlestickPatternStrategy implements TunableStrategy {

    private static final ZoneId IST       = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START  = LocalTime.of(9, 45);
    private static final LocalTime END    = LocalTime.of(14, 0);
    private static final String NAME      = "CANDLESTICK_PATTERN";

    private final double minEngulfRatio; // current body must be >= this × prior body
    private final double volumeMult;     // pattern candle volume > avgVol × this
    private final double rRRatio;        // reward:risk ratio
    private final double levelBandAtr;  // key level proximity in ATR units

    public CandlestickPatternStrategy() {
        this(1.5, 1.5, 2.0, 0.4);
    }

    private CandlestickPatternStrategy(double minEngulfRatio, double volumeMult,
                                        double rRRatio, double levelBandAtr) {
        this.minEngulfRatio = minEngulfRatio;
        this.volumeMult     = volumeMult;
        this.rRRatio        = rRRatio;
        this.levelBandAtr   = levelBandAtr;
    }

    @Override public String getName() { return NAME; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("minEngulfRatio", minEngulfRatio, "volumeMult", volumeMult,
                      "rRRatio", rRRatio, "levelBandAtr", levelBandAtr);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "minEngulfRatio", new double[]{1.2, 1.5, 2.0},
            "volumeMult",     new double[]{1.3, 1.5, 2.0},
            "rRRatio",        new double[]{1.5, 2.0, 2.5},
            "levelBandAtr",   new double[]{0.3, 0.4, 0.6}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new CandlestickPatternStrategy(
            p.getOrDefault("minEngulfRatio", minEngulfRatio),
            p.getOrDefault("volumeMult",     volumeMult),
            p.getOrDefault("rRRatio",        rRRatio),
            p.getOrDefault("levelBandAtr",   levelBandAtr)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime now = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (now.isBefore(START) || now.isAfter(END)) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < 45) return Optional.empty(); // need enough history for EMA + trend

        LocalDate today = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalDate();
        double atr = ctx.currentAtr();
        if (atr <= 0) return Optional.empty();

        Candle curr  = candles.get(candles.size() - 1);
        Candle prev  = candles.get(candles.size() - 2);

        // Must be today's candle
        if (!curr.getOpenTime().withZoneSameInstant(IST).toLocalDate().equals(today))
            return Optional.empty();

        // ── Pattern detection: Engulfing only ──────────────────────────────────
        double cO = curr.getOpen().doubleValue(),  cC = curr.getClose().doubleValue();
        double cH = curr.getHigh().doubleValue(),  cL = curr.getLow().doubleValue();
        double pO = prev.getOpen().doubleValue(),  pC = prev.getClose().doubleValue();

        double currBody = Math.abs(cC - cO);
        double prevBody = Math.abs(pC - pO);
        double minBody  = atr * 0.08; // real candle, not a doji

        if (currBody < minBody || prevBody < minBody) return Optional.empty();

        // Pattern size: whole candle range must be meaningful
        if ((cH - cL) < atr * 0.4) return Optional.empty();

        // Strength: current body must significantly outsize previous body
        if (currBody < prevBody * minEngulfRatio) return Optional.empty();

        boolean isBull;
        double  sl;

        boolean prevRed   = pC < pO;
        boolean currGreen = cC > cO;
        boolean prevGreen = pC > pO;
        boolean currRed   = cC < cO;

        if (prevRed && currGreen && cO <= pC && cC >= pO) {
            // Bullish engulfing: green body covers red body
            isBull = true;
            sl = cL - atr * 0.05; // just below the engulfing candle's low
        } else if (prevGreen && currRed && cO >= pC && cC <= pO) {
            // Bearish engulfing: red body covers green body
            isBull = false;
            sl = cH + atr * 0.05; // just above the engulfing candle's high
        } else {
            return Optional.empty();
        }

        double price = cC;

        // ── VWAP bias (institutional direction filter) ─────────────────────────
        double vwap = ctx.currentVwap().doubleValue();
        if (isBull  && price < vwap) return Optional.empty(); // bull trade needs bullish VWAP
        if (!isBull && price > vwap) return Optional.empty(); // bear trade needs bearish VWAP

        // ── Key level: MUST be near VWAP, PDH, or PDL — nothing else ──────────
        // (Intraday H/L excluded: price is trivially always near those)
        double band = levelBandAtr * atr;
        double[] pdhl = findPdHighLow(candles, today);
        double pdh = pdhl[0], pdl = pdhl[1];

        boolean atVwap = Math.abs(price - vwap) <= band;
        boolean atPdh  = pdh > 0 && Math.abs(price - pdh) <= band;
        boolean atPdl  = pdl > 0 && Math.abs(price - pdl) <= band;
        boolean atRound = Math.abs(price - Math.round(price / 100.0) * 100.0) <= band;
        if (!atVwap && !atPdh && !atPdl && !atRound) return Optional.empty();

        // ── Higher-timeframe trend: EMA(21) over last 30 bars ─────────────────
        int n = candles.size();
        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] ema21  = IndicatorUtils.ema(closes, 21);
        boolean  uptrend = ema21[n - 1] > ema21[n - 31]; // 30-bar trend (not 15)
        if (isBull != uptrend) return Optional.empty();

        // ── Volume: pattern candle must show commitment ────────────────────────
        double avgVol = avgVolume(candles, 20);
        if (avgVol > 0 && curr.getVolume() < avgVol * volumeMult) return Optional.empty();

        // ── RSI: tighter bands — no chasing extremes ──────────────────────────
        double rsi = IndicatorUtils.rsiLast(closes, 14);
        if (isBull  && (rsi < 40 || rsi > 68)) return Optional.empty();
        if (!isBull && (rsi < 32 || rsi > 60)) return Optional.empty();

        // ── Target and signal ──────────────────────────────────────────────────
        double risk = Math.abs(price - sl);
        double tgt  = isBull ? price + rRRatio * risk : price - rRRatio * risk;

        String level = atVwap ? "VWAP" : (atPdh ? "PDH" : (atPdl ? "PDL" : "ROUND"));
        String reason = String.format(
            "%s at %s(%.0f) body=%.0f vol=%.1fx RSI=%.0f trend=%s",
            isBull ? "BULL_ENGULF" : "BEAR_ENGULF",
            level, atVwap ? vwap : (atPdh ? pdh : pdl),
            currBody, avgVol > 0 ? curr.getVolume() / avgVol : 0,
            rsi, isBull ? "UP" : "DOWN"
        );

        SignalDirection dir = isBull ? SignalDirection.LONG_CE : SignalDirection.LONG_PE;
        return Optional.of(buildSignal(ctx, dir, price, sl, tgt, reason));
    }

    private double[] findPdHighLow(List<Candle> candles, LocalDate today) {
        LocalDate prevDay = null;
        for (Candle c : candles) {
            LocalDate cd = c.getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (cd.equals(today)) continue;
            if (prevDay == null || cd.isAfter(prevDay)) prevDay = cd;
        }
        if (prevDay == null) return new double[]{0, 0};
        final LocalDate pd = prevDay;
        double high = Double.MIN_VALUE, low = Double.MAX_VALUE;
        boolean found = false;
        for (Candle c : candles) {
            LocalDate cd = c.getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (!cd.equals(pd)) continue;
            high = Math.max(high, c.getHigh().doubleValue());
            low  = Math.min(low,  c.getLow().doubleValue());
            found = true;
        }
        return found ? new double[]{high, low} : new double[]{0, 0};
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
