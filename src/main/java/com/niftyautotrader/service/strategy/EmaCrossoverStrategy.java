package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.Signal;
import com.niftyautotrader.model.SignalDirection;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Strategy 1: EMA(9/21) crossover on 5m chart with RSI filter and VWAP confirmation.
 *
 * Long CE signal when:
 *   - EMA9 crosses above EMA21 (bullish crossover)
 *   - RSI(14) is between 45 and 70 (not overbought, trend continuation zone)
 *   - Close is above VWAP (price confirmation)
 *   - Market is trending (ADX > 25)
 *
 * Long PE signal when:
 *   - EMA9 crosses below EMA21 (bearish crossover)
 *   - RSI(14) is between 30 and 55
 *   - Close is below VWAP
 *   - Market is trending
 */
@Component
public class EmaCrossoverStrategy implements TradingStrategy {

    private static final int EMA_FAST = 9;
    private static final int EMA_SLOW = 21;
    private static final int RSI_PERIOD = 14;
    private static final int MIN_CANDLES = EMA_SLOW + RSI_PERIOD + 5;

    @Override
    public String getName() { return "EMA_CROSSOVER_9_21"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = toDoubles(candles, CandleField.CLOSE);
        double[] ema9 = IndicatorUtils.ema(closes, EMA_FAST);
        double[] ema21 = IndicatorUtils.ema(closes, EMA_SLOW);

        int last = closes.length - 1;
        int prev = last - 1;

        double rsi = IndicatorUtils.rsiLast(closes, RSI_PERIOD);
        double vwap = ctx.currentVwap().doubleValue();
        double lastClose = closes[last];

        // Bullish crossover: EMA9 just crossed above EMA21
        boolean bullishCross = ema9[prev] <= ema21[prev] && ema9[last] > ema21[last];
        // Bearish crossover: EMA9 just crossed below EMA21
        boolean bearishCross = ema9[prev] >= ema21[prev] && ema9[last] < ema21[last];

        if (bullishCross && rsi >= 45 && rsi <= 70 && lastClose > vwap && ctx.isTrendingMarket()) {
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, lastClose,
                String.format("EMA9(%.2f) crossed above EMA21(%.2f), RSI=%.1f, close(%.2f) > VWAP(%.2f), ADX=%.1f",
                    ema9[last], ema21[last], rsi, lastClose, vwap, ctx.adx())));
        }

        if (bearishCross && rsi >= 30 && rsi <= 55 && lastClose < vwap && ctx.isTrendingMarket()) {
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, lastClose,
                String.format("EMA9(%.2f) crossed below EMA21(%.2f), RSI=%.1f, close(%.2f) < VWAP(%.2f), ADX=%.1f",
                    ema9[last], ema21[last], rsi, lastClose, vwap, ctx.adx())));
        }

        return Optional.empty();
    }

    private Signal buildSignal(MarketContext ctx, SignalDirection dir, double price, String reason) {
        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now());
        s.setStrategyName(getName());
        s.setSymbol(ctx.symbol());
        s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(price));
        s.setSuggestedStopLoss(BigDecimal.valueOf(price - ctx.currentAtr() * 1.5));
        s.setSuggestedTarget(BigDecimal.valueOf(price + ctx.currentAtr() * 3.0));
        s.setLots(1);
        s.setReasoning(reason);
        return s;
    }

    private double[] toDoubles(List<Candle> candles, CandleField field) {
        return candles.stream()
            .mapToDouble(c -> switch (field) {
                case CLOSE -> c.getClose().doubleValue();
                case HIGH  -> c.getHigh().doubleValue();
                case LOW   -> c.getLow().doubleValue();
                case OPEN  -> c.getOpen().doubleValue();
            }).toArray();
    }

    private enum CandleField { OPEN, HIGH, LOW, CLOSE }
}
