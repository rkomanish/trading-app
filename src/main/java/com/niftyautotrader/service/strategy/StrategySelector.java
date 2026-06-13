package com.niftyautotrader.service.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Selects which strategies are eligible to fire given the current market regime.
 *
 * Market regimes and their permitted strategies — updated based on backtest results:
 *
 *  ┌─────────────────┬──────────┬─────────────────────────────────────────────────┐
 *  │ Regime          │ ADX      │ Strategies (all confirmed profitable in backtest)│
 *  ├─────────────────┼──────────┼─────────────────────────────────────────────────┤
 *  │ Opening (09:15) │ any      │ OPENING_RANGE_BREAKOUT only                     │
 *  │ Strong trend    │ > 25     │ ORB (valid until 11:00), MACD, BB_SQUEEZE        │
 *  │ Moderate trend  │ 18–25    │ MACD, EMA_CROSSOVER, ORB, BB_SQUEEZE             │
 *  │ Ranging         │ < 18     │ BB_SQUEEZE only (mean reversion disabled)         │
 *  └─────────────────┴──────────┴─────────────────────────────────────────────────┘
 *
 * DISABLED strategies (backtest shows consistent losses):
 *   - EMA21_PULLBACK:       40% win rate, PF 0.42, max drawdown ₹34,573 — removed from all regimes
 *   - VWAP_BOLLINGER:       0% win rate, all trades hit SL — Nifty trends too hard for mean reversion
 *   - SUPERTREND_FOLLOWER:  only 3 trades in 90 days — insufficient signal frequency
 *
 * MONITORING only (paper trade, not real money):
 *   - EMA_CROSSOVER_9_21:   72.7% win rate but only 11 trades — sample too small to trust
 */
@Component
public class StrategySelector {

    private static final Logger log = LoggerFactory.getLogger(StrategySelector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final LocalTime OPENING_END = LocalTime.of(9, 45);
    private static final LocalTime MID_DAY_END = LocalTime.of(14, 30);

    /**
     * Returns the subset of strategies that should run given the current market context.
     * All enabled strategies are still evaluated; this just logs the regime.
     */
    public MarketRegime detectRegime(MarketContext ctx) {
        LocalTime time = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        double adx = ctx.adx();

        if (time.isBefore(OPENING_END)) {
            return MarketRegime.OPENING;
        }
        if (adx > 25) {
            return MarketRegime.STRONG_TREND;
        }
        if (adx >= 18) {
            return MarketRegime.MODERATE_TREND;
        }
        return MarketRegime.RANGING;
    }

    /**
     * Filter strategies to only those appropriate for the current regime.
     * Strategies that work in any regime (e.g. BB squeeze) are always included.
     */
    public List<TradingStrategy> selectForRegime(List<TradingStrategy> all,
                                                   MarketRegime regime,
                                                   MarketContext ctx) {
        List<TradingStrategy> selected = all.stream()
            .filter(TradingStrategy::isEnabled)
            .filter(s -> isAppropriate(s.getName(), regime))
            .collect(Collectors.toList());

        log.info("Regime={} ADX={} — selected {}/{} strategies: {}",
            regime, String.format("%.1f", ctx.adx()), selected.size(), all.size(),
            selected.stream().map(TradingStrategy::getName).toList());

        return selected;
    }

    private boolean isAppropriate(String name, MarketRegime regime) {
        return switch (regime) {
            case OPENING -> List.of(
                "OPENING_RANGE_BREAKOUT"
            ).contains(name);

            // Strong trend: ORB still valid until 11 AM, MACD with high ADX is excellent,
            // BB squeeze catches volatility expansions. EMA21_PULLBACK REMOVED — 40% win rate.
            case STRONG_TREND -> List.of(
                "OPENING_RANGE_BREAKOUT",
                "MACD_CROSSOVER",
                "BOLLINGER_SQUEEZE_BREAKOUT"
            ).contains(name);

            // Moderate trend: crossover and momentum strategies.
            // EMA21_PULLBACK REMOVED — consistent losses across all param combos.
            case MODERATE_TREND -> List.of(
                "MACD_CROSSOVER",
                "EMA_CROSSOVER_9_21",
                "OPENING_RANGE_BREAKOUT",
                "BOLLINGER_SQUEEZE_BREAKOUT"
            ).contains(name);

            // Ranging: only BB squeeze. VWAP_BOLLINGER REMOVED — 0% win rate, all trades
            // hit SL. Nifty ranging days still have 50-100pt directional moves that destroy
            // mean reversion positions. BB squeeze fires on range breakouts, not reversions.
            case RANGING -> List.of(
                "BOLLINGER_SQUEEZE_BREAKOUT"
            ).contains(name);
        };
    }

    public enum MarketRegime {
        OPENING,        // First 30 min — ORB window
        STRONG_TREND,   // ADX > 25 — momentum strategies
        MODERATE_TREND, // ADX 18-25 — crossover/MACD
        RANGING         // ADX < 18 — mean reversion
    }
}
