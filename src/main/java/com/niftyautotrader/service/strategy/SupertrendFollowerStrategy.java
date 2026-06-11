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
 * Strategy 3: Supertrend Follower with trailing stop.
 *
 * Uses 15m candles. Generates signal on Supertrend flip (trend change).
 *   - Flip to bullish → LONG_CE
 *   - Flip to bearish → LONG_PE
 *
 * Supertrend itself acts as trailing stop-loss.
 * Requires ADX > 20 to avoid whipsaws in sideways markets.
 */
@Component
public class SupertrendFollowerStrategy implements TradingStrategy {

    private static final int ST_PERIOD = 7;        // faster response on 15m
    private static final double ST_MULTIPLIER = 2.0; // tighter band → realistic SL/target
    private static final int MIN_CANDLES = ST_PERIOD + 5;

    @Override
    public String getName() { return "SUPERTREND_FOLLOWER"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        List<Candle> candles = ctx.candles15m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();
        if (ctx.adx() < 20) return Optional.empty(); // filter choppy markets

        double[] highs  = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();

        var st = IndicatorUtils.supertrend(highs, lows, closes, ST_PERIOD, ST_MULTIPLIER);
        int last = closes.length - 1;
        int prev = last - 1;

        boolean flippedBullish = !st.isBullish()[prev] && st.isBullish()[last];
        boolean flippedBearish = st.isBullish()[prev] && !st.isBullish()[last];

        double stLine = st.supertrend()[last];
        double lastClose = closes[last];

        if (flippedBullish) {
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, lastClose, stLine,
                String.format("Supertrend flipped bullish, ST=%.2f, close=%.2f, ADX=%.1f",
                    stLine, lastClose, ctx.adx())));
        }
        if (flippedBearish) {
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, lastClose, stLine,
                String.format("Supertrend flipped bearish, ST=%.2f, close=%.2f, ADX=%.1f",
                    stLine, lastClose, ctx.adx())));
        }

        return Optional.empty();
    }

    private Signal buildSignal(MarketContext ctx, SignalDirection dir, double entry,
                                double stStop, String reason) {
        double rr = 2.0;
        double sl = dir == SignalDirection.LONG_CE ? stStop : stStop;
        double target = dir == SignalDirection.LONG_CE
            ? entry + (entry - sl) * rr
            : entry - (sl - entry) * rr;

        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now());
        s.setStrategyName(getName());
        s.setSymbol(ctx.symbol());
        s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(entry));
        s.setSuggestedStopLoss(BigDecimal.valueOf(sl));
        s.setSuggestedTarget(BigDecimal.valueOf(target));
        s.setLots(1);
        s.setReasoning(reason);
        return s;
    }
}
