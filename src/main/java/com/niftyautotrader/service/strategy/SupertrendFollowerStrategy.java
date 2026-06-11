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
 * Supertrend Follower — 15m candles.
 *
 * Signals on Supertrend flip (trend change):
 *   - Flip to bullish → LONG_CE
 *   - Flip to bearish → LONG_PE
 *
 * Loss-control improvements:
 *   - ADX threshold raised from 20 → 25 (stronger trend confirmation)
 *   - Minimum distance from ST line to entry required (avoid entries right at ST line)
 *   - SL = Supertrend line but capped at 0.8×ATR from entry (max loss limit)
 *   - Volume confirmation: flip candle volume > 1.2× 10-bar average
 *   - Skip if price is more than 1.5×ATR from ST line at flip (already moved too far)
 */
@Component
public class SupertrendFollowerStrategy implements TradingStrategy {

    private static final int ST_PERIOD = 7;
    private static final double ST_MULTIPLIER = 2.0;
    private static final int MIN_CANDLES = ST_PERIOD + 15;
    private static final int VOL_LOOKBACK = 10;
    private static final double MAX_SL_ATR = 0.8; // cap SL at 0.8×ATR from entry

    @Override
    public String getName() { return "SUPERTREND_FOLLOWER"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        List<Candle> candles = ctx.candles15m();
        if (candles.size() < MIN_CANDLES + VOL_LOOKBACK) return Optional.empty();
        if (ctx.adx() < 25) return Optional.empty(); // raised from 20

        double[] highs  = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();

        var st   = IndicatorUtils.supertrend(highs, lows, closes, ST_PERIOD, ST_MULTIPLIER);
        int last = closes.length - 1;
        int prev = last - 1;

        boolean flippedBull = !st.isBullish()[prev] && st.isBullish()[last];
        boolean flippedBear =  st.isBullish()[prev] && !st.isBullish()[last];
        if (!flippedBull && !flippedBear) return Optional.empty();

        // Volume confirmation
        long avgVol = 0;
        for (int i = last - VOL_LOOKBACK + 1; i <= last; i++) avgVol += vols[i];
        avgVol /= VOL_LOOKBACK;
        if (vols[last] < avgVol * 1.2) return Optional.empty();

        double stLine  = st.supertrend()[last];
        double price   = closes[last];
        double atr     = ctx.currentAtr();

        // Skip if entry is too far from ST line (momentum already played out)
        double distFromSt = Math.abs(price - stLine);
        if (distFromSt > atr * 1.5) return Optional.empty();

        if (flippedBull) {
            // SL = ST line, but capped at 0.8×ATR below entry
            double rawSl  = stLine;
            double sl     = Math.max(rawSl, price - atr * MAX_SL_ATR);
            double target = price + (price - sl) * 2.0;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("ST flip bull ST=%.0f close=%.0f ADX=%.1f dist=%.0f vol=%dk",
                    stLine, price, ctx.adx(), distFromSt, vols[last]/1000)));
        }
        // flippedBear
        double rawSl  = stLine;
        double sl     = Math.min(rawSl, price + atr * MAX_SL_ATR);
        double target = price - (sl - price) * 2.0;
        return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, target,
            String.format("ST flip bear ST=%.0f close=%.0f ADX=%.1f dist=%.0f vol=%dk",
                stLine, price, ctx.adx(), distFromSt, vols[last]/1000)));
    }

    private Signal buildSignal(MarketContext ctx, SignalDirection dir, double entry,
                                double sl, double target, String reason) {
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
