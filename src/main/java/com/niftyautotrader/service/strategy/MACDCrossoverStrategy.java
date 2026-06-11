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
 * Strategy 5: MACD Signal-Line Crossover on 5m candles.
 *
 * MACD = EMA(12) - EMA(26). Signal line = EMA(9) of MACD.
 * Histogram = MACD - Signal.
 *
 * LONG_CE when: histogram flips positive (MACD crosses above signal)
 *   AND close > VWAP AND ADX > 18
 *
 * LONG_PE when: histogram flips negative (MACD crosses below signal)
 *   AND close < VWAP AND ADX > 18
 *
 * MACD is smoother than raw EMA crossover — fewer whipsaws, better trend confirmation.
 * Trade window: 09:30 – 14:00 IST only.
 */
@Component
public class MACDCrossoverStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 30);
    private static final LocalTime END   = LocalTime.of(14, 0);

    private static final int FAST = 12, SLOW = 26, SIGNAL = 9;
    private static final int MIN_CANDLES = SLOW + SIGNAL + 5;

    @Override
    public String getName() { return "MACD_CROSSOVER"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END)) return Optional.empty();
        if (ctx.adx() < 18) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] emaFast = IndicatorUtils.ema(closes, FAST);
        double[] emaSlow = IndicatorUtils.ema(closes, SLOW);

        int n = closes.length;
        double[] macd = new double[n];
        for (int i = 0; i < n; i++) macd[i] = emaFast[i] - emaSlow[i];

        double[] signalLine = IndicatorUtils.ema(macd, SIGNAL);
        int last = n - 1, prev = n - 2;

        double histNow  = macd[last] - signalLine[last];
        double histPrev = macd[prev] - signalLine[prev];

        boolean bullFlip = histPrev <= 0 && histNow > 0;
        boolean bearFlip = histPrev >= 0 && histNow < 0;

        double price = closes[last];
        double vwap  = ctx.currentVwap().doubleValue();
        double atr   = ctx.currentAtr();

        if (bullFlip && price > vwap) {
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, atr,
                String.format("MACD bull flip hist=%.2f→%.2f VWAP=%.2f ADX=%.1f",
                    histPrev, histNow, vwap, ctx.adx())));
        }
        if (bearFlip && price < vwap) {
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, atr,
                String.format("MACD bear flip hist=%.2f→%.2f VWAP=%.2f ADX=%.1f",
                    histPrev, histNow, vwap, ctx.adx())));
        }
        return Optional.empty();
    }

    private Signal signal(MarketContext ctx, SignalDirection dir, double price, double atr, String reason) {
        double sl     = dir == SignalDirection.LONG_CE ? price - atr * 1.2 : price + atr * 1.2;
        double target = dir == SignalDirection.LONG_CE ? price + atr * 2.4 : price - atr * 2.4;
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
