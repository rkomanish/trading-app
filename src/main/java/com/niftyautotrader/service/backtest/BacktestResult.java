package com.niftyautotrader.service.backtest;

import java.math.BigDecimal;
import java.util.List;

public record BacktestResult(
    String strategyName,
    int totalTrades,
    int winningTrades,
    int losingTrades,
    double winRate,
    BigDecimal totalGrossPnl,
    BigDecimal totalCosts,
    BigDecimal totalNetPnl,
    BigDecimal avgWin,
    BigDecimal avgLoss,
    BigDecimal expectancyPerTrade,
    BigDecimal maxDrawdown,
    double profitFactor,
    List<BigDecimal> equityCurve,
    boolean promotable,    // positive expectancy after costs across walk-forward windows
    String verdict         // human-readable promotion verdict
) {}
