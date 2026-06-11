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
 * Market regimes and their preferred strategies:
 *
 *  ┌─────────────────┬──────────┬────────────────────────────────────────────┐
 *  │ Regime          │ ADX      │ Best Strategies                            │
 *  ├─────────────────┼──────────┼────────────────────────────────────────────┤
 *  │ Opening (09:15) │ any      │ OPENING_RANGE_BREAKOUT                     │
 *  │ Strong trend    │ > 25     │ SUPERTREND_FOLLOWER, EMA21_PULLBACK         │
 *  │ Moderate trend  │ 18–25    │ MACD_CROSSOVER, EMA_CROSSOVER_9_21         │
 *  │ Ranging         │ < 18     │ VWAP_BOLLINGER_REVERSION, BB squeeze       │
 *  │ Squeeze setup   │ any      │ BOLLINGER_SQUEEZE_BREAKOUT                 │
 *  └─────────────────┴──────────┴────────────────────────────────────────────┘
 *
 * The StrategyEngine calls this before evaluating each strategy tick.
 * Only enabled strategies for the current regime are evaluated.
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

            case STRONG_TREND -> List.of(
                "SUPERTREND_FOLLOWER",
                "EMA21_PULLBACK",
                "EMA_CROSSOVER_9_21",
                "BOLLINGER_SQUEEZE_BREAKOUT"
            ).contains(name);

            case MODERATE_TREND -> List.of(
                "MACD_CROSSOVER",
                "EMA_CROSSOVER_9_21",
                "EMA21_PULLBACK",
                "OPENING_RANGE_BREAKOUT",
                "BOLLINGER_SQUEEZE_BREAKOUT"
            ).contains(name);

            case RANGING -> List.of(
                "VWAP_BOLLINGER_REVERSION",
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
