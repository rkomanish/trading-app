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
import java.util.*;

/**
 * Gap Momentum Strategy.
 *
 * Nifty frequently opens with a gap driven by overnight global cues (SGX, Dow futures).
 * Large gaps (>0.35%) tend to CONTINUE in the gap direction rather than fill,
 * especially when confirmed by the first 30 minutes of price action.
 *
 * Logic:
 *   1. Calculate gap = (today open) / (yesterday close) − 1
 *   2. After 9:45 AM (30-min confirmation), check if price is still holding gap direction
 *   3. Price must be above/below VWAP (institutional bias confirmation)
 *   4. RSI confirms momentum (not extreme)
 *   5. Enter on gap continuation, SL = VWAP (gap fill = stop out)
 *
 * One trade per day. Entry window: 9:45 AM – 11:30 AM only.
 */
@Component
public class GapMomentumStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 45); // 30 min after open
    private static final LocalTime END   = LocalTime.of(11, 30);
    private static final int RSI_PERIOD = 14;

    private final double minGapPct;     // minimum gap % to trigger (e.g., 0.35 = 0.35%)
    private final double slAtrCap;      // SL distance in ATR
    private final double targetMult;    // target = targetMult × risk

    public GapMomentumStrategy() {
        this(0.35, 0.5, 2.0);
    }

    private GapMomentumStrategy(double minGapPct, double slAtrCap, double targetMult) {
        this.minGapPct = minGapPct;
        this.slAtrCap = slAtrCap;
        this.targetMult = targetMult;
    }

    @Override public String getName() { return "GAP_MOMENTUM"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("minGapPct", minGapPct, "slAtrCap", slAtrCap, "targetMult", targetMult);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "minGapPct",  new double[]{0.20, 0.35, 0.50},
            "slAtrCap",   new double[]{0.3, 0.5, 0.7},
            "targetMult", new double[]{1.5, 2.0, 2.5}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new GapMomentumStrategy(
            p.getOrDefault("minGapPct",  minGapPct),
            p.getOrDefault("slAtrCap",   slAtrCap),
            p.getOrDefault("targetMult", targetMult)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime now = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (now.isBefore(START) || now.isAfter(END)) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < RSI_PERIOD + 10) return Optional.empty();

        LocalDate today = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalDate();

        // Get today's opening candle (9:15)
        Candle todayOpen = null;
        for (Candle c : candles) {
            LocalDate cd = c.getOpenTime().withZoneSameInstant(IST).toLocalDate();
            LocalTime ct = c.getOpenTime().withZoneSameInstant(IST).toLocalTime();
            if (cd.equals(today) && !ct.isBefore(LocalTime.of(9, 15))) {
                todayOpen = c;
                break;
            }
        }
        if (todayOpen == null) return Optional.empty();

        // Get previous day's closing price (last candle of previous day)
        double prevClose = Double.NaN;
        LocalDate prevDate = null;
        for (int i = candles.size() - 1; i >= 0; i--) {
            LocalDate cd = candles.get(i).getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (cd.equals(today)) continue;
            if (prevDate == null) prevDate = cd;
            if (!cd.equals(prevDate)) break;
            prevClose = candles.get(i).getClose().doubleValue();
            break; // we only need the last candle of prev day
        }
        // Actually get the LAST (most recent) candle of previous day
        for (int i = candles.size() - 1; i >= 0; i--) {
            LocalDate cd = candles.get(i).getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (cd.equals(today)) continue;
            prevClose = candles.get(i).getClose().doubleValue();
            break;
        }
        if (Double.isNaN(prevClose) || prevClose == 0) return Optional.empty();

        double gapPct = (todayOpen.getOpen().doubleValue() - prevClose) / prevClose * 100.0;

        // Not enough gap
        if (Math.abs(gapPct) < minGapPct) return Optional.empty();

        double price = candles.get(candles.size() - 1).getClose().doubleValue();
        double vwap  = ctx.currentVwap().doubleValue();
        double atr   = ctx.currentAtr();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double rsi = IndicatorUtils.rsiLast(closes, RSI_PERIOD);

        if (gapPct > 0) {
            // Gap up: price must hold above prev close and above VWAP, RSI not overbought
            if (price > prevClose && price > vwap && rsi < 72) {
                double sl  = Math.max(vwap - atr * 0.1, price - atr * slAtrCap);
                double tgt = price + (price - sl) * targetMult;
                return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                    String.format("Gap+%.2f%% price=%.0f vwap=%.0f sl=%.0f tgt=%.0f",
                        gapPct, price, vwap, sl, tgt)));
            }
        } else {
            // Gap down: price must hold below prev close and below VWAP, RSI not oversold
            if (price < prevClose && price < vwap && rsi > 28) {
                double sl  = Math.min(vwap + atr * 0.1, price + atr * slAtrCap);
                double tgt = price - (sl - price) * targetMult;
                return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                    String.format("Gap%.2f%% price=%.0f vwap=%.0f sl=%.0f tgt=%.0f",
                        gapPct, price, vwap, sl, tgt)));
            }
        }
        return Optional.empty();
    }

    private Signal signal(MarketContext ctx, SignalDirection dir, double price,
                           double sl, double target, String reason) {
        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now());
        s.setStrategyName(getName());
        s.setSymbol(ctx.symbol()); s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(price));
        s.setSuggestedStopLoss(BigDecimal.valueOf(sl));
        s.setSuggestedTarget(BigDecimal.valueOf(target));
        s.setLots(1); s.setReasoning(reason);
        return s;
    }
}
