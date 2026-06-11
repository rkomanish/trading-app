package com.niftyautotrader.service.backtest;

import java.math.BigDecimal;
import java.util.List;

/**
 * Summary card for one strategy used in the marketplace / performance view.
 */
public record StrategyCard(
    String name,
    String displayName,
    String strategyType,        // e.g. "Trend Following", "Mean Reversion", "Breakout"
    String tradingStyle,        // "Intraday"
    String timeframe,           // primary candle timeframe used
    int minCapitalInr,          // approximate margin/capital in INR

    // Rolling returns (as % of minCapital)
    BigDecimal return30d,
    BigDecimal return90d,
    BigDecimal return180d,

    int trades30d,
    int trades90d,
    int trades180d,

    double winRate30d,          // 0–1
    double profitFactor30d,
    BigDecimal maxDrawdown30d,
    BigDecimal expectancy30d,   // per-trade expectancy INR
    boolean promotable,
    String verdict,

    List<BigDecimal> equityCurve30d   // for sparkline rendering
) {}
