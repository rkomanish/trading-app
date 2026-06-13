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
import java.util.*;

/**
 * Previous Day High / Low Breakout (PDH/PDL).
 *
 * One of the highest-probability intraday setups on Nifty:
 *   - Institutional order blocks cluster at previous day's high/low
 *   - Breakout above PDH = trapped sellers → quick squeeze up
 *   - Breakdown below PDL = trapped buyers → quick squeeze down
 *
 * Entry: 9:30 AM – 1:00 PM only (avoid thin afternoon sessions)
 * One trade per day maximum.
 * SL placed just inside PDH/PDL (institutional support zone).
 */
@Component
public class PreviousDayBreakoutStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 30);
    private static final LocalTime END   = LocalTime.of(13, 0);

    private final double bufferAtrMult; // how far ABOVE pdh / BELOW pdl to confirm breakout
    private final double slAtrCap;      // stop loss distance in ATR units
    private final double targetMult;    // target = targetMult × risk
    private final double volMult;       // current bar volume must exceed avgVol × volMult

    // Lean defaults: tight buffer, tight SL, 2:1 R:R, moderate volume confirmation
    public PreviousDayBreakoutStrategy() {
        this(0.05, 0.4, 2.0, 1.0);
    }

    private PreviousDayBreakoutStrategy(double bufferAtrMult, double slAtrCap,
                                         double targetMult, double volMult) {
        this.bufferAtrMult = bufferAtrMult;
        this.slAtrCap = slAtrCap;
        this.targetMult = targetMult;
        this.volMult = volMult;
    }

    @Override public String getName() { return "PDH_PDL_BREAKOUT"; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of("bufferAtrMult", bufferAtrMult, "slAtrCap", slAtrCap,
            "targetMult", targetMult, "volMult", volMult);
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "bufferAtrMult", new double[]{0.03, 0.05, 0.10},
            "slAtrCap",      new double[]{0.3, 0.4, 0.5},
            "targetMult",    new double[]{1.5, 2.0, 2.5}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new PreviousDayBreakoutStrategy(
            p.getOrDefault("bufferAtrMult", bufferAtrMult),
            p.getOrDefault("slAtrCap",      slAtrCap),
            p.getOrDefault("targetMult",    targetMult),
            p.getOrDefault("volMult",       volMult)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime now = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (now.isBefore(START) || now.isAfter(END)) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < 50) return Optional.empty();

        LocalDate today = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalDate();

        // Find previous trading day's candles
        double pdHigh = Double.MIN_VALUE, pdLow = Double.MAX_VALUE;
        long pdVolSum = 0; int pdBars = 0;
        LocalDate pdDate = null;

        for (int i = candles.size() - 1; i >= 0; i--) {
            LocalDate cd = candles.get(i).getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (cd.equals(today)) continue;
            if (pdDate == null) pdDate = cd;
            if (!cd.equals(pdDate)) break; // only look at most-recent previous day

            pdHigh = Math.max(pdHigh, candles.get(i).getHigh().doubleValue());
            pdLow  = Math.min(pdLow,  candles.get(i).getLow().doubleValue());
            pdVolSum += candles.get(i).getVolume();
            pdBars++;
        }

        if (pdBars < 20) return Optional.empty(); // need full previous day
        if (pdHigh == Double.MIN_VALUE) return Optional.empty();

        double avgPdVol = (double) pdVolSum / pdBars;
        double atr      = ctx.currentAtr();
        double price    = candles.get(candles.size() - 1).getClose().doubleValue();
        double vol      = candles.get(candles.size() - 1).getVolume();

        // Volume confirmation: current bar must show activity
        if (avgPdVol > 0 && vol < avgPdVol * volMult) return Optional.empty();

        double buffer = atr * bufferAtrMult;

        if (price > pdHigh + buffer) {
            // Breakout above PDH → buy CE
            double sl  = Math.max(pdHigh - atr * 0.1, price - atr * slAtrCap);
            double tgt = price + (price - sl) * targetMult;
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, tgt,
                String.format("PDH break price=%.0f PDH=%.0f sl=%.0f tgt=%.0f",
                    price, pdHigh, sl, tgt)));
        }
        if (price < pdLow - buffer) {
            // Breakdown below PDL → buy PE
            double sl  = Math.min(pdLow + atr * 0.1, price + atr * slAtrCap);
            double tgt = price - (sl - price) * targetMult;
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, tgt,
                String.format("PDL break price=%.0f PDL=%.0f sl=%.0f tgt=%.0f",
                    price, pdLow, sl, tgt)));
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
