package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.Signal;
import com.niftyautotrader.model.SignalDirection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OpeningRangeBreakoutStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ORB_START = LocalTime.of(9, 15);
    // 45-minute range (9:15–10:00): first 15 min is pure noise on Nifty.
    // Professional ORB uses 30–60 min to let the market settle.
    private static final LocalTime ORB_END   = LocalTime.of(10, 0);

    private final LocalTime entryCutoff;
    private final double maxOrbAtrMult;
    private final double slAtrCap;
    private final double volMult;

    // Tighter SL: 0.5×ATR ≈ 25 pts = ₹1,875 max loss per lot (75 units × 25 pts)
    // Target = 2× risk = ₹3,750. Capital per trade: ~₹15,000–₹25,000 (ATM option premium × 75)
    public OpeningRangeBreakoutStrategy() {
        // entryCutoff 13:00 — don't chase breakouts after 1 PM (afternoon liquidity dries up)
        this(LocalTime.of(13, 0), 2.5, 0.5, 1.2);
    }

    private OpeningRangeBreakoutStrategy(LocalTime entryCutoff, double maxOrbAtrMult,
                                          double slAtrCap, double volMult) {
        this.entryCutoff = entryCutoff; this.maxOrbAtrMult = maxOrbAtrMult;
        this.slAtrCap = slAtrCap; this.volMult = volMult;
    }

    @Override public String getName() { return "OPENING_RANGE_BREAKOUT"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("maxOrbAtrMult", maxOrbAtrMult, "slAtrCap", slAtrCap, "volMult", volMult);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "maxOrbAtrMult", new double[]{1.5, 2.0, 2.5},
            "slAtrCap",      new double[]{0.3, 0.4, 0.5},
            "volMult",       new double[]{1.2, 1.5, 2.0}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new OpeningRangeBreakoutStrategy(
            entryCutoff,
            p.getOrDefault("maxOrbAtrMult", maxOrbAtrMult),
            p.getOrDefault("slAtrCap",      slAtrCap),
            p.getOrDefault("volMult",       volMult)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime now = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (now.isBefore(ORB_END) || now.isAfter(entryCutoff)) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < 6) return Optional.empty();

        LocalDate today = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalDate();
        double atr = ctx.currentAtr();

        double orbHigh = Double.MIN_VALUE, orbLow = Double.MAX_VALUE;
        long orbVol = 0; int orbBars = 0;
        for (Candle c : candles) {
            LocalTime ct = c.getOpenTime().withZoneSameInstant(IST).toLocalTime();
            LocalDate cd = c.getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (!cd.equals(today)) continue;
            if (!ct.isBefore(ORB_START) && ct.isBefore(ORB_END)) {
                orbHigh = Math.max(orbHigh, c.getHigh().doubleValue());
                orbLow  = Math.min(orbLow,  c.getLow().doubleValue());
                orbVol += c.getVolume(); orbBars++;
            }
        }
        if (orbBars == 0) return Optional.empty();
        double orbWidth = orbHigh - orbLow;
        // Skip event days: ORB wider than 2× ATR = Budget/RBI/expiry gap — risk is unquantifiable
        if (orbWidth > atr * maxOrbAtrMult) return Optional.empty();
        // Also skip if ORB is too narrow (< 0.1% of price) = pre-market data issue
        double priceLevel = (orbHigh + orbLow) / 2.0;
        if (orbWidth < priceLevel * 0.001) return Optional.empty();

        Candle latest = candles.get(candles.size() - 1);
        double price  = latest.getClose().doubleValue();
        double vol    = latest.getVolume();
        double avgOrbVol = orbBars > 0 ? (double) orbVol / orbBars : 0;
        if (avgOrbVol > 0 && vol < avgOrbVol * volMult) return Optional.empty();

        // Buffer = 0.05× ATR to avoid entering on tiny pokes above range
        double buffer = atr * 0.05;
        if (price > orbHigh + buffer) {
            // SL just below the ORB low — that's the invalidation level
            double sl  = orbLow - atr * 0.05;
            double tgt = price + (price - sl) * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("ORB bull close=%.0f hi=%.0f lo=%.0f sl=%.0f tgt=%.0f",
                    price, orbHigh, orbLow, sl, tgt)));
        }
        if (price < orbLow - buffer) {
            // SL just above the ORB high — that's the invalidation level
            double sl  = orbHigh + atr * 0.05;
            double tgt = price - (sl - price) * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                String.format("ORB bear close=%.0f lo=%.0f hi=%.0f sl=%.0f tgt=%.0f",
                    price, orbLow, orbHigh, sl, tgt)));
        }
        return Optional.empty();
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
