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
import java.util.Optional;

/**
 * EMA(9/21) crossover on 5m chart.
 *
 * Entry filters (all must pass):
 *   - EMA9 just crossed above/below EMA21
 *   - RSI in momentum zone (not overbought/oversold)
 *   - Close on correct side of VWAP
 *   - ADX > 20 (trending market)
 *   - EMA9 clearly diverging from EMA21 (slope filter — avoids weak crosses)
 *   - Volume above 80% of 20-bar average (real move, not noise)
 *
 * SL: 0.8×ATR (tighter than before)
 * Target: 1.8×ATR → easier to reach, more TARGET hits vs TIME_EXIT
 * R:R: minimum 2:1 enforced
 */
@Component
public class EmaCrossoverStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime TRADE_START = LocalTime.of(9, 30);
    private static final LocalTime TRADE_END   = LocalTime.of(14, 0);

    private static final int EMA_FAST = 9, EMA_SLOW = 21, RSI_PERIOD = 14;
    private static final int VOL_LOOKBACK = 20;
    private static final int MIN_CANDLES = EMA_SLOW + RSI_PERIOD + VOL_LOOKBACK + 5;

    @Override
    public String getName() { return "EMA_CROSSOVER_9_21"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(TRADE_START) || t.isAfter(TRADE_END)) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();

        double[] ema9  = IndicatorUtils.ema(closes, EMA_FAST);
        double[] ema21 = IndicatorUtils.ema(closes, EMA_SLOW);

        int last = closes.length - 1, prev = last - 1;

        double rsi      = IndicatorUtils.rsiLast(closes, RSI_PERIOD);
        double vwap     = ctx.currentVwap().doubleValue();
        double atr      = ctx.currentAtr();
        double price    = closes[last];

        // Volume filter: current volume > 80% of 20-bar average
        long avgVol = 0;
        for (int i = last - VOL_LOOKBACK + 1; i <= last; i++) avgVol += vols[i];
        avgVol /= VOL_LOOKBACK;
        if (vols[last] < avgVol * 0.8) return Optional.empty();

        // Slope filter: EMA9 must be clearly diverging (not a weak/flat cross)
        double emaDivergence = Math.abs(ema9[last] - ema21[last]) / ema21[last];
        if (emaDivergence < 0.0003) return Optional.empty(); // less than 0.03% apart → weak cross

        boolean bullCross = ema9[prev] <= ema21[prev] && ema9[last] > ema21[last];
        boolean bearCross = ema9[prev] >= ema21[prev] && ema9[last] < ema21[last];

        if (bullCross && rsi >= 45 && rsi <= 70 && price > vwap && ctx.adx() > 20) {
            double sl     = price - atr * 0.8;
            double target = price + atr * 1.8;
            if (!validRR(price, sl, target, true)) return Optional.empty();
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("EMA9(%.0f)>EMA21(%.0f) RSI=%.1f VWAP=%.0f ADX=%.1f vol=%dk",
                    ema9[last], ema21[last], rsi, vwap, ctx.adx(), vols[last]/1000)));
        }

        if (bearCross && rsi >= 30 && rsi <= 55 && price < vwap && ctx.adx() > 20) {
            double sl     = price + atr * 0.8;
            double target = price - atr * 1.8;
            if (!validRR(price, sl, target, false)) return Optional.empty();
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("EMA9(%.0f)<EMA21(%.0f) RSI=%.1f VWAP=%.0f ADX=%.1f vol=%dk",
                    ema9[last], ema21[last], rsi, vwap, ctx.adx(), vols[last]/1000)));
        }

        return Optional.empty();
    }

    private Signal buildSignal(MarketContext ctx, SignalDirection dir, double price,
                                double sl, double target, String reason) {
        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now());
        s.setStrategyName(getName());
        s.setSymbol(ctx.symbol());
        s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(price));
        s.setSuggestedStopLoss(BigDecimal.valueOf(sl));
        s.setSuggestedTarget(BigDecimal.valueOf(target));
        s.setLots(1);
        s.setReasoning(reason);
        return s;
    }

    /** Minimum 1.5:1 reward-to-risk. */
    private boolean validRR(double entry, double sl, double target, boolean isBull) {
        double risk   = Math.abs(entry - sl);
        double reward = isBull ? target - entry : entry - target;
        return risk > 0 && reward / risk >= 1.5;
    }
}
