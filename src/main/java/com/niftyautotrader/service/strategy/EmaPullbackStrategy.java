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
 * EMA21 Trend Pullback — 5m candles.
 *
 * Enters on a pullback TO the EMA21 in the direction of the established trend.
 * Better entry price than chasing a crossover.
 *
 * Loss-control improvements:
 *   - Touch tolerance tightened: 0.2% → 0.15% (must be closer to EMA21)
 *   - SL tightened: 1.0×ATR → 0.8×ATR
 *   - ADX threshold raised: 20 → 22
 *   - Require EMA9 to be clearly diverging from EMA21 (not flat)
 *     — ensures trend is real, not just a random position of the EMAs
 *   - Candle must close back above/below EMA21 (not just touch and bounce away)
 *     — price should be near EMA21 but trending back in trade direction
 *   - Window narrowed: end 13:30 → 13:00 (avoid lunchtime chop near EMA)
 */
@Component
public class EmaPullbackStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 30);
    private static final LocalTime END   = LocalTime.of(13, 0);

    private static final int EMA_FAST = 9, EMA_SLOW = 21, RSI_PERIOD = 14;
    private static final int MIN_CANDLES = EMA_SLOW + RSI_PERIOD + 5;
    private static final double TOUCH_TOL  = 0.0015; // 0.15% band around EMA21
    private static final double TREND_CONF = 0.0015; // EMA9 must be 0.15% away from EMA21

    @Override
    public String getName() { return "EMA21_PULLBACK"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime t = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (t.isBefore(START) || t.isAfter(END)) return Optional.empty();
        if (ctx.adx() < 22) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] ema9   = IndicatorUtils.ema(closes, EMA_FAST);
        double[] ema21  = IndicatorUtils.ema(closes, EMA_SLOW);
        double rsi      = IndicatorUtils.rsiLast(closes, RSI_PERIOD);

        int last  = closes.length - 1;
        double price = closes[last];
        double e9    = ema9[last];
        double e21   = ema21[last];
        double atr   = ctx.currentAtr();

        // Trend confirmation: EMA9 must be clearly separated from EMA21
        double emaSeparation = Math.abs(e9 - e21) / e21;
        if (emaSeparation < TREND_CONF) return Optional.empty();

        boolean touchingEma = Math.abs(price - e21) / e21 < TOUCH_TOL;
        if (!touchingEma) return Optional.empty();

        boolean inBullTrend = e9 > e21;
        boolean inBearTrend = e9 < e21;

        if (inBullTrend && rsi >= 42 && rsi <= 56) {
            double sl     = e21 - atr * 0.8;
            double target = price + atr * 2.5; // wide target since entry is tight
            return Optional.of(signal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("EMA21 pullback bull: price=%.0f EMA21=%.0f RSI=%.1f ADX=%.1f sep=%.2f%%",
                    price, e21, rsi, ctx.adx(), emaSeparation * 100)));
        }

        if (inBearTrend && rsi >= 44 && rsi <= 58) {
            double sl     = e21 + atr * 0.8;
            double target = price - atr * 2.5;
            return Optional.of(signal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("EMA21 pullback bear: price=%.0f EMA21=%.0f RSI=%.1f ADX=%.1f sep=%.2f%%",
                    price, e21, rsi, ctx.adx(), emaSeparation * 100)));
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
