package com.niftyautotrader.repository;

import com.niftyautotrader.model.BacktestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BacktestRunRepository extends JpaRepository<BacktestRun, Long> {

    /** All runs for a strategy, newest first */
    List<BacktestRun> findByStrategyNameOrderByRunAtDesc(String strategyName);

    /** Last 50 runs across all strategies */
    List<BacktestRun> findTop50ByOrderByRunAtDesc();

    /** Best optimized run per strategy (highest score) */
    @Query("SELECT b FROM BacktestRun b WHERE b.strategyName = :name AND b.isOptimized = true " +
           "ORDER BY b.optimizerScore DESC")
    List<BacktestRun> findBestOptimizedRuns(String name);

    /** Manual (non-optimized) runs for a strategy */
    List<BacktestRun> findByStrategyNameAndIsOptimizedFalseOrderByRunAtDesc(String strategyName);

    /** All optimizer trial runs for a specific strategy, sorted by score desc */
    List<BacktestRun> findByStrategyNameAndIsOptimizedTrueOrderByOptimizerScoreDesc(String strategyName);
}
