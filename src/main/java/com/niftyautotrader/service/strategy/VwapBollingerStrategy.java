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
 * Strategy 4: VWAP + Bollinger Band mean-reversion on 5m candles.
 *
 * LONG_CE when:
 *   - Close touches or pierces lower Bollinger Band (20, 2.0)
 *   - Close is near or below VWAP (within 0.3×ATR)
 *   - RSI(14) < 38 (oversold) and the previous RSI was even lower (turning up)
 *   - Not in last 60 minutes of session (avoid 15:00–15:30 noise)
 *
 * LONG_PE when:
 *   - Close touches or pierces upper Bollinger Band
 *   - Close is near or above VWAP
 *   - RSI(14) > 62 (overbought) and previous RSI was even higher (turning down)
 *
 * Stop-loss: 1.5×ATR beyond entry; target: 2×ATR (1:2 R:R minimum).
 */
@Component
public class VwapBollingerStrategy implements TradingStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CUTOFF = LocalTime.of(15, 0);
    private static final int BB_PERIOD = 20;
    private static final double BB_STDDEV = 2.0;
    private static final int RSI_PERIOD = 14;
    private static final int MIN_CANDLES = BB_PERIOD + RSI_PERIOD + 5;

    @Override
    public String getName() { return "VWAP_BOLLINGER_REVERSION"; }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        List<Candle> candles = ctx.candles5m();
        if (candles.size() < MIN_CANDLES) return Optional.empty();

        LocalTime nowIst = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (nowIst.isAfter(CUTOFF)) return Optional.empty();

        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] highs  = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        long[]   vols   = candles.stream().mapToLong(Candle::getVolume).toArray();

        var bb = IndicatorUtils.bollingerBandsLast(closes, BB_PERIOD, BB_STDDEV);
        double[] rsiArr = IndicatorUtils.rsi(closes, RSI_PERIOD);
        int last = closes.length - 1;
        double rsiNow  = rsiArr[last];
        double rsiPrev = rsiArr[last - 1];

        double vwap = IndicatorUtils.vwapLast(highs, lows, closes, vols);
        double atr  = ctx.currentAtr();
        double price = closes[last];

        // LONG_CE: price at lower band, oversold RSI turning up, price near/below VWAP
        boolean atLowerBand = price <= bb.lower() * 1.001;
        boolean rsiTurningUp = rsiNow < 38 && rsiNow > rsiPrev;
        boolean nearOrBelowVwap = price <= vwap + atr * 0.3;

        if (atLowerBand && rsiTurningUp && nearOrBelowVwap) {
            double sl = price - atr * 1.5;
            double target = price + atr * 2.5;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_CE, price, sl, target,
                String.format("BB lower(%.2f) touch, RSI=%.1f↑, VWAP=%.2f, ATR=%.2f",
                    bb.lower(), rsiNow, vwap, atr)));
        }

        // LONG_PE: price at upper band, overbought RSI turning down, price near/above VWAP
        boolean atUpperBand = price >= bb.upper() * 0.999;
        boolean rsiTurningDown = rsiNow > 62 && rsiNow < rsiPrev;
        boolean nearOrAboveVwap = price >= vwap - atr * 0.3;

        if (atUpperBand && rsiTurningDown && nearOrAboveVwap) {
            double sl = price + atr * 1.5;
            double target = price - atr * 2.5;
            return Optional.of(buildSignal(ctx, SignalDirection.LONG_PE, price, sl, target,
                String.format("BB upper(%.2f) touch, RSI=%.1f↓, VWAP=%.2f, ATR=%.2f",
                    bb.upper(), rsiNow, vwap, atr)));
        }

        return Optional.empty();
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
