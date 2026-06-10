package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Snapshot of market data provided to each strategy evaluation.
 * Immutable value object constructed by StrategyEngine before each evaluation run.
 */
public record MarketContext(
    String symbol,
    ZonedDateTime evaluatedAt,
    List<Candle> candles1m,
    List<Candle> candles5m,
    List<Candle> candles15m,
    BigDecimal lastPrice,
    BigDecimal currentVwap,
    double currentAtr,
    double adx,
    boolean isTrendingMarket   // ADX > 25
) {
    /** Most recent 5m candle */
    public Candle latestCandle5m() {
        return candles5m.isEmpty() ? null : candles5m.get(candles5m.size() - 1);
    }

    /** Most recent 15m candle */
    public Candle latestCandle15m() {
        return candles15m.isEmpty() ? null : candles15m.get(candles15m.size() - 1);
    }
}
