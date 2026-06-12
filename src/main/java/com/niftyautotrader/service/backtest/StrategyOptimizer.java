package com.niftyautotrader.service.backtest;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.service.strategy.TunableStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Grid-search optimizer for TunableStrategy implementations.
 *
 * For each strategy, it:
 *   1. Reads the paramGrid() — which params to tune and what values to try
 *   2. Generates every combination (cartesian product)
 *   3. Runs BacktestEngine on each combination
 *   4. Scores each result: expectancy × winRate × sqrt(profitFactor)
 *      (balanced metric — rewards expectancy AND consistency)
 *   5. Returns the best combination with a comparison against default params
 *
 * Typical grid size: 3 params × 3 values = 27 combinations.
 * Each backtest on 30 days of 5m data takes ~100ms → 27 × 100ms = 2.7 seconds.
 */
@Service
public class StrategyOptimizer {

    private static final Logger log = LoggerFactory.getLogger(StrategyOptimizer.class);

    private final BacktestEngine engine;

    public StrategyOptimizer(BacktestEngine engine) {
        this.engine = engine;
    }

    /**
     * Run grid search on the given strategy over the provided candles.
     *
     * @param strategy  a TunableStrategy to optimize
     * @param candles   5m candles for the target period
     * @param windows   walk-forward windows to use (same as manual backtest)
     */
    public OptimizationResult optimize(TunableStrategy strategy,
                                        List<Candle> candles,
                                        int windows) {
        Map<String, double[]> grid = strategy.paramGrid();
        Map<String, Double>   defaultParams = strategy.currentParams();

        // Run backtest with current (default) params first
        BacktestResult defaultResult = safeRun(strategy, candles, windows);
        double defaultScore = score(defaultResult);

        // Generate all parameter combinations
        List<Map<String, Double>> combinations = cartesianProduct(grid);
        log.info("[Optimizer] {} — testing {} combinations on {} candles",
            strategy.getName(), combinations.size(), candles.size());

        List<OptimizationResult.TrialResult> trials = new ArrayList<>();
        BacktestResult bestResult = defaultResult;
        Map<String, Double> bestParams = new HashMap<>(defaultParams);
        double bestScore = defaultScore;

        for (Map<String, Double> params : combinations) {
            try {
                var variant = strategy.withParams(params);
                var result  = safeRun(variant, candles, windows);
                double s    = score(result);

                trials.add(new OptimizationResult.TrialResult(
                    Collections.unmodifiableMap(new LinkedHashMap<>(params)),
                    s,
                    result.totalTrades(),
                    result.winRate(),
                    result.profitFactor(),
                    result.totalNetPnl(),
                    result.expectancyPerTrade(),
                    result.promotable()
                ));

                if (s > bestScore) {
                    bestScore  = s;
                    bestResult = result;
                    bestParams = new HashMap<>(params);
                }
            } catch (Exception e) {
                log.warn("[Optimizer] trial failed for {}: {}", params, e.getMessage());
            }
        }

        // Sort trials by score descending
        trials.sort(Comparator.comparingDouble(OptimizationResult.TrialResult::score).reversed());

        String improvement = buildImprovementSummary(defaultResult, bestResult, defaultScore, bestScore);
        log.info("[Optimizer] {} — best score={} params={}", strategy.getName(), String.format("%.4f", bestScore), bestParams);

        return new OptimizationResult(
            strategy.getName(),
            Collections.unmodifiableMap(bestParams),
            bestScore,
            bestResult,
            Collections.unmodifiableMap(defaultParams),
            defaultResult,
            Collections.unmodifiableList(trials),
            combinations.size(),
            improvement
        );
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    /**
     * Score = expectancy (INR) × winRate × sqrt(profitFactor).
     * Rewards high per-trade profit, consistency, and balanced win/loss size.
     * Returns 0 if no trades or negative expectancy.
     */
    static double score(BacktestResult r) {
        if (r.totalTrades() < 3) return 0;
        double exp = r.expectancyPerTrade().doubleValue();
        if (exp <= 0) return 0;
        return exp * r.winRate() * Math.sqrt(Math.max(r.profitFactor(), 0.01));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BacktestResult safeRun(com.niftyautotrader.service.strategy.TradingStrategy s,
                                    List<Candle> candles, int windows) {
        try {
            return engine.runWalkForward(s, candles, windows);
        } catch (Exception e) {
            return emptyResult(s.getName());
        }
    }

    /** Cartesian product of a parameter grid. */
    static List<Map<String, Double>> cartesianProduct(Map<String, double[]> grid) {
        List<String> keys = new ArrayList<>(grid.keySet());
        List<Map<String, Double>> results = new ArrayList<>();
        results.add(new LinkedHashMap<>());

        for (String key : keys) {
            List<Map<String, Double>> expanded = new ArrayList<>();
            for (double val : grid.get(key)) {
                for (Map<String, Double> existing : results) {
                    Map<String, Double> copy = new LinkedHashMap<>(existing);
                    copy.put(key, val);
                    expanded.add(copy);
                }
            }
            results = expanded;
        }
        return results;
    }

    private String buildImprovementSummary(BacktestResult def, BacktestResult best,
                                            double defScore, double bestScore) {
        if (bestScore <= defScore) {
            return String.format("No improvement found. Default params remain best " +
                "(score=%.2f, expectancy=₹%.0f/trade)", defScore, def.expectancyPerTrade());
        }
        double pctGain = defScore > 0 ? (bestScore - defScore) / defScore * 100 : 100;
        return String.format("Score improved %.1f%% (%.2f→%.2f). " +
            "Expectancy: ₹%.0f→₹%.0f/trade. Win rate: %.1f%%→%.1f%%",
            pctGain, defScore, bestScore,
            def.expectancyPerTrade().doubleValue(),
            best.expectancyPerTrade().doubleValue(),
            def.winRate() * 100, best.winRate() * 100);
    }

    private BacktestResult emptyResult(String name) {
        return new BacktestResult(name, 0, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
            List.of(BigDecimal.ZERO), false, "NOT PROMOTABLE — optimization failed",
            List.of(), Map.of(), null, null, 0);
    }
}
