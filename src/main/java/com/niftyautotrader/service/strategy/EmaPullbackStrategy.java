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
 * Strategy 6: EMA21 Trend Pullback on 5m candles.
 *
 * Waits for a trend to establish (price clearly above/below EMA21),
 * then enters on a pullback TO the EMA21 rather than on a crossover.
 * This gives better entry prices than chasing a crossover.
 *
 * LONG_CE (buy dip):
 *   - EMA9 > EMA21 (bullish trend)
 *   - Price pulled back to within 0.1% of EMA21 (touching the EMA)
 *   - RSI(14) between 40-55 (not overbought, not too oversold)
 *   - ADX > 20 (trending)
 *   - Time: 09:30 – 13:30 IST
 *
 * LONG_PE (sell bounce):
 *   - EMA9 < EMA21 (bearish trend)
 *   - Price bounced up to within 0.1% of EMA21
 *   - RSI(14) between 45-60
 *   - ADX > 20
 */
@Component
public class EmaPullbackStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 30);
    private static final LocalTime END   = LocalTime.of(13, 30);

    private static final int EMA_FAST = 9, EMA_SLOW = 21, RSI_PERIOD = 14;
    private static final int MIN_CANDLES = EMA_SLOW + RSI_PERIOD + 5;
    private static final double TOUCH_TOLERANCE = 0.002; // 0.2% band around EMA

    @Override
    public String getName() { return "EMA21_PULLBACK"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END)) return Optional.empty();
        if (ctx.adx() < 20) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] ema9   = IndicatorUtils.ema(closes, EMA_FAST);
        double[] ema21  = IndicatorUtils.ema(closes, EMA_SLOW);
        double rsi      = IndicatorUtils.rsiLast(closes, RSI_PERIOD);

        int last = closes.length - 1;
        double price    = closes[last];
        double e9       = ema9[last];
        double e21      = ema21[last];
        double atr      = ctx.currentAtr();

        boolean inBullTrend  = e9 > e21 * 1.001; // EMA9 clearly above EMA21
        boolean touchingEma  = Math.abs(price - e21) / e21 < TOUCH_TOLERANCE;
        boolean inBearTrend  = e9 < e21 * 0.999;
        boolean touchingEmaB = Math.abs(price - e21) / e21 < TOUCH_TOLERANCE;

        if (inBullTrend && touchingEma && rsi >= 40 && rsi <= 58) {
            double sl     = e21 - atr * 1.0; // SL just below EMA21
            double target = price + atr * 2.5;
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("EMA21 pullback bull: price=%.2f EMA21=%.2f RSI=%.1f ADX=%.1f",
                    price, e21, rsi, ctx.adx())));
        }

        if (inBearTrend && touchingEmaB && rsi >= 42 && rsi <= 60) {
            double sl     = e21 + atr * 1.0; // SL just above EMA21
            double target = price - atr * 2.5;
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("EMA21 pullback bear: price=%.2f EMA21=%.2f RSI=%.1f ADX=%.1f",
                    price, e21, rsi, ctx.adx())));
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
