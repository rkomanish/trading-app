package com.niftyautotrader.service.strategy;

import java.util.Map;

/**
 * Extension of TradingStrategy that supports parameter grid search.
 *
 * The optimizer calls paramGrid() to discover which parameters to tune
 * and what values to try, then calls withParams() to create a lightweight
 * clone of the strategy with those values applied.
 *
 * Convention for param names:
 *   adxMin        – minimum ADX for entry
 *   slAtrMult     – stop-loss as multiple of ATR
 *   targetAtrMult – target as multiple of ATR
 *   rsiLo / rsiHi – RSI range bounds
 *   volMult       – volume confirmation multiplier
 */
public interface TunableStrategy extends TradingStrategy {

    /** Current parameter values (used to show "what the strategy is using") */
    Map<String, Double> currentParams();

    /**
     * Parameter grid: each key maps to an array of candidate values.
     * The optimizer tries every combination (grid search).
     * Keep the grid small (≤ 3 params × 3 values = 27 combos max) for speed.
     */
    Map<String, double[]> paramGrid();

    /**
     * Return a new instance of this strategy configured with the given params.
     * Missing keys fall back to the strategy's own defaults.
     * The returned instance is NOT a Spring bean — it is used only for backtesting.
     */
    TradingStrategy withParams(Map<String, Double> params);
}
