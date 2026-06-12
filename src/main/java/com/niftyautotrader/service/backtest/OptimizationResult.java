package com.niftyautotrader.service.backtest;

import java.util.List;
import java.util.Map;

/**
 * Result returned by StrategyOptimizer.
 * Contains the best parameter set found and all trials sorted by score.
 */
public record OptimizationResult(
    String strategyName,
    Map<String, Double> bestParams,      // winning parameter combination
    double bestScore,                    // expectancy × winRate × profitFactor
    BacktestResult bestResult,           // full backtest result for the best params
    Map<String, Double> defaultParams,   // what the strategy was using before
    BacktestResult defaultResult,        // backtest result with default params
    List<TrialResult> allTrials,         // all combinations tried, sorted by score desc
    int totalTrials,
    String improvement                   // human-readable summary of gain
) {
    public record TrialResult(
        Map<String, Double> params,
        double score,
        int trades,
        double winRate,
        double profitFactor,
        java.math.BigDecimal netPnl,
        java.math.BigDecimal expectancy,
        boolean promotable
    ) {}
}
