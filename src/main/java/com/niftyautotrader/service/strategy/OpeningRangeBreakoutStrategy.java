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
    private static final LocalTime ORB_END   = LocalTime.of(9, 30);

    private final LocalTime entryCutoff;
    private final double maxOrbAtrMult;
    private final double slAtrCap;
    private final double volMult;

    public OpeningRangeBreakoutStrategy() {
        this(LocalTime.of(11, 0), 2.0, 1.0, 1.5);
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
            "slAtrCap",      new double[]{0.8, 1.0, 1.2},
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
        if (orbHigh - orbLow > atr * maxOrbAtrMult) return Optional.empty();

        Candle latest = candles.get(candles.size() - 1);
        double price  = latest.getClose().doubleValue();
        double vol    = latest.getVolume();
        double avgOrbVol = orbBars > 0 ? (double) orbVol / orbBars : 0;
        if (avgOrbVol > 0 && vol < avgOrbVol * volMult) return Optional.empty();

        double buffer = atr * 0.10;
        if (price > orbHigh + buffer) {
            double sl = Math.max(orbLow, price - atr * slAtrCap);
            double tgt = price + (price - sl) * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("ORB bull close=%.0f hi=%.0f sl=%.0f tgt=%.0f", price, orbHigh, sl, tgt)));
        }
        if (price < orbLow - buffer) {
            double sl = Math.min(orbHigh, price + atr * slAtrCap);
            double tgt = price - (sl - price) * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                String.format("ORB bear close=%.0f lo=%.0f sl=%.0f tgt=%.0f", price, orbLow, sl, tgt)));
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
