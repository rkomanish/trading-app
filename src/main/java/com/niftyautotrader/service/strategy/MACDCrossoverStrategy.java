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
 * MACD Signal-Line Crossover — 5m candles.
 *
 * Histogram flip + VWAP + ADX + RSI confirmation.
 *
 * Loss-control improvements:
 *   - Added RSI confirmation: CE requires RSI > 50, PE requires RSI < 50
 *     (ensures momentum direction aligns with MACD signal)
 *   - SL tightened: 1.2×ATR → 0.8×ATR
 *   - Target reduced: 2.4×ATR → 1.8×ATR (more TARGET hits vs TIME_EXIT)
 *   - ADX threshold raised: 18 → 22
 *   - Volume filter: breakout candle volume > 90% of 20-bar average
 *   - Require histogram growing (not just flipped — avoid small crosses)
 */
@Component
public class MACDCrossoverStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 45);
    private static final LocalTime END   = LocalTime.of(13, 30);

    private static final int FAST = 12, SLOW = 26, SIGNAL_P = 9, RSI_PERIOD = 14;
    private static final int VOL_LOOKBACK = 20;
    private static final int MIN_CANDLES = SLOW + SIGNAL_P + RSI_PERIOD + VOL_LOOKBACK + 5;

    @Override
    public String getName() { return "MACD_CROSSOVER"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END)) return Optional.empty();
        if (ctx.adx() < 22) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();

        // Volume filter
        int n = closes.length;
        long avgVol = 0;
        for (int i = n - VOL_LOOKBACK; i < n; i++) avgVol += vols[i];
        avgVol /= VOL_LOOKBACK;
        if (vols[n - 1] < avgVol * 0.9) return Optional.empty();

        double[] emaFast   = IndicatorUtils.ema(closes, FAST);
        double[] emaSlow   = IndicatorUtils.ema(closes, SLOW);
        double[] macd      = new double[n];
        for (int i = 0; i < n; i++) macd[i] = emaFast[i] - emaSlow[i];
        double[] signalLine = IndicatorUtils.ema(macd, SIGNAL_P);

        int last = n - 1, prev = n - 2, prev2 = n - 3;
        double histNow   = macd[last]  - signalLine[last];
        double histPrev  = macd[prev]  - signalLine[prev];
        double histPrev2 = macd[prev2] - signalLine[prev2];

        boolean bullFlip = histPrev <= 0 && histNow > 0;
        boolean bearFlip = histPrev >= 0 && histNow < 0;

        // Histogram must be growing (not just flipped by tiny amount)
        boolean histGrowingBull = bullFlip && Math.abs(histNow) > Math.abs(histPrev);
        boolean histGrowingBear = bearFlip && Math.abs(histNow) > Math.abs(histPrev);

        double price = closes[last];
        double vwap  = ctx.currentVwap().doubleValue();
        double atr   = ctx.currentAtr();
        double rsi   = IndicatorUtils.rsiLast(closes, RSI_PERIOD);

        if (histGrowingBull && price > vwap && rsi > 50) {
            double sl     = price - atr * 0.8;
            double target = price + atr * 1.8;
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("MACD bull hist=%.1f→%.1f RSI=%.1f VWAP=%.0f ADX=%.1f",
                    histPrev, histNow, rsi, vwap, ctx.adx())));
        }
        if (histGrowingBear && price < vwap && rsi < 50) {
            double sl     = price + atr * 0.8;
            double target = price - atr * 1.8;
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("MACD bear hist=%.1f→%.1f RSI=%.1f VWAP=%.0f ADX=%.1f",
                    histPrev, histNow, rsi, vwap, ctx.adx())));
        }
        return Optional.empty();
    }

    private Signal signal(MarketContext ctx, SignalDirection dir, double price,
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
}
