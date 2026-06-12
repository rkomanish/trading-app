package com.niftyautotrader.service.backtest;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import com.niftyautotrader.service.strategy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that generates synthetic Nifty 50 candle data,
 * runs the grid optimizer on each strategy, prints results,
 * and optionally sends to Claude API for Part C enhancement.
 *
 * Run with: mvn test -Dtest=OptimizerIntegrationTest -pl . -Dspring.profiles.active=test
 */
@SpringBootTest
@ActiveProfiles("test")
class OptimizerIntegrationTest {

    @Autowired StrategyOptimizer optimizer;
    @Autowired StrategyEnhancementService enhancer;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Synthetic data generator ──────────────────────────────────────────────

    /**
     * Generates realistic Nifty 50 intraday 5m candles for `days` trading days.
     * Uses a geometric Brownian motion with realistic Nifty parameters:
     *   - Base level ~22,000
     *   - Daily drift 0.05%
     *   - Intraday volatility: ATR ~50-80 points per 5m bar
     *   - Volume: 50k–300k per 5m bar with opening surge
     */
    static List<Candle> syntheticCandles(int days) {
        List<Candle> candles = new ArrayList<>();
        Random rng = new Random(42); // fixed seed for reproducibility

        LocalDate date = LocalDate.of(2024, 1, 2);
        double price = 22000.0;

        for (int d = 0; d < days; d++) {
            // Skip weekends
            while (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.plusDays(1);
            }

            // Market-wide day bias: trending up/down/flat
            double dayBias = (rng.nextDouble() - 0.48) * 0.003; // slight bullish bias
            double dayVol = 0.0008 + rng.nextDouble() * 0.0005; // 0.08-0.13% per bar

            // Opening gap ±0.3%
            price *= (1 + (rng.nextDouble() - 0.5) * 0.006);

            // 9:15 to 15:25 IST → 75 five-minute bars
            ZonedDateTime barTime = date.atTime(9, 15).atZone(IST);

            double open = price;
            for (int bar = 0; bar < 75; bar++) {
                double ret = dayBias + dayVol * gaussianClamped(rng);
                double close = open * (1 + ret);
                double range = Math.abs(open - close) + open * 0.0003 * (1 + rng.nextDouble());
                double high = Math.max(open, close) + range * rng.nextDouble() * 0.5;
                double low  = Math.min(open, close) - range * rng.nextDouble() * 0.5;

                // Volume: opening surge, midday lull, closing surge
                long vol;
                if (bar < 6) vol = 150000 + (long)(rng.nextDouble() * 150000); // opening
                else if (bar > 65) vol = 100000 + (long)(rng.nextDouble() * 200000); // closing
                else vol = 30000 + (long)(rng.nextDouble() * 80000);

                Candle c = new Candle();
                c.setSymbol("NIFTY");
                c.setTimeframe("5m");
                c.setOpenTime(barTime);
                c.setOpen(bd(open));
                c.setHigh(bd(high));
                c.setLow(bd(low));
                c.setClose(bd(close));
                c.setVolume(vol);
                candles.add(c);

                open = close;
                barTime = barTime.plusMinutes(5);
            }
            price = open; // carry price to next day
            date = date.plusDays(1);
        }
        return candles;
    }

    private static double gaussianClamped(Random rng) {
        return Math.max(-3, Math.min(3, rng.nextGaussian()));
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(Math.round(v * 100.0) / 100.0);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void optimizeAllStrategiesAndPrintResults() {
        List<Candle> candles = syntheticCandles(90); // 90 trading days ≈ 4.5 months
        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.printf("  Generated %d candles over ~90 trading days%n", candles.size());
        System.out.println("═══════════════════════════════════════════════════════════\n");

        List<TunableStrategy> strategies = List.of(
            new EmaCrossoverStrategy(),
            new OpeningRangeBreakoutStrategy(),
            new SupertrendFollowerStrategy(),
            new VwapBollingerStrategy(),
            new MACDCrossoverStrategy(),
            new EmaPullbackStrategy(),
            new BollingerSqueezeStrategy()
        );

        List<OptimizationResult> results = new ArrayList<>();

        for (TunableStrategy s : strategies) {
            System.out.printf("─── Optimizing: %s ───%n", s.getName());
            long t0 = System.currentTimeMillis();
            OptimizationResult opt = optimizer.optimize(s, candles, 4);
            long elapsed = System.currentTimeMillis() - t0;

            results.add(opt);
            BacktestResult best = opt.bestResult();

            System.out.printf("  Trials: %d  Time: %dms%n", opt.totalTrials(), elapsed);
            System.out.printf("  Default → Best: %s%n", opt.improvement());
            System.out.printf("  Best params: %s%n", opt.bestParams());
            System.out.printf("  Trades: %d  Win%%: %.1f%%  PF: %.2f  Expectancy: ₹%.0f  Net P&L: ₹%.0f%n",
                best.totalTrades(), best.winRate() * 100, best.profitFactor(),
                best.expectancyPerTrade(), best.totalNetPnl());
            System.out.printf("  Max Drawdown: ₹%.0f  Promotable: %s%n",
                best.maxDrawdown(), best.promotable() ? "✓ YES" : "✗ NO");
            System.out.printf("  Verdict: %s%n", best.verdict());

            // Exit reason breakdown
            long targets  = best.trades().stream().filter(t -> "TARGET".equals(t.exitReason())).count();
            long slHits   = best.trades().stream().filter(t -> "STOP_LOSS".equals(t.exitReason())).count();
            long trails   = best.trades().stream().filter(t -> "TRAIL_STOP".equals(t.exitReason())).count();
            long timeExit = best.trades().stream().filter(t -> "TIME_EXIT".equals(t.exitReason())).count();
            System.out.printf("  Exits → TARGET:%d  SL:%d  TRAIL:%d  TIME:%d%n",
                targets, slHits, trails, timeExit);

            // Top 3 trials
            System.out.println("  Top 3 parameter combos:");
            opt.allTrials().stream().limit(3).forEach(tr ->
                System.out.printf("    win=%.1f%%  pf=%.2f  exp=₹%.0f  params=%s%n",
                    tr.winRate() * 100, tr.profitFactor(), tr.expectancy(), tr.params()));
            System.out.println();
        }

        // Summary table
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.printf("%-30s %8s %8s %10s %10s %10s%n",
            "Strategy", "Trades", "Win%", "PF", "Exp/trade", "Net P&L");
        System.out.println("──────────────────────────────────────────────────────────");
        results.forEach(opt -> {
            BacktestResult r = opt.bestResult();
            System.out.printf("%-30s %8d %7.1f%% %10.2f %9s ₹%8.0f%s%n",
                opt.strategyName(), r.totalTrades(), r.winRate() * 100,
                r.profitFactor(), "₹" + String.format("%.0f", r.expectancyPerTrade()),
                r.totalNetPnl(), r.promotable() ? " ✓" : "");
        });
        System.out.println("══════════════════════════════════════════════════════════");

        // At least some strategies should produce trades
        assertThat(results.stream().mapToInt(o -> o.bestResult().totalTrades()).sum())
            .as("Total trades across all strategies should be > 0")
            .isGreaterThan(0);
    }

    @Test
    void enhanceBestStrategyWithClaude() {
        List<Candle> candles = syntheticCandles(90);

        // Pick EMA Crossover for demonstration
        TunableStrategy strategy = new EmaCrossoverStrategy();
        OptimizationResult opt = optimizer.optimize(strategy, candles, 4);

        System.out.println("\n═══ Running Claude AI Enhancement (Part C) ═══");
        System.out.println("Strategy: " + opt.strategyName());
        System.out.println("Sending optimization results to Claude API...\n");

        StrategyEnhancementService.EnhancementSuggestion suggestion =
            enhancer.analyse(opt, opt.bestResult());

        System.out.println("─── DIAGNOSIS ───");
        System.out.println(suggestion.summary());
        System.out.println("\n─── RULE CHANGES ───");
        suggestion.ruleChanges().forEach(r -> System.out.println("  • " + r));
        System.out.println("\n─── NEW FILTERS ───");
        suggestion.newFilters().forEach(f -> System.out.println("  + " + f));
        System.out.println("\n─── REMOVE FILTERS ───");
        suggestion.removeFilters().forEach(r -> System.out.println("  - " + r));
        System.out.println("\n─── EXPECTED IMPACT ───");
        System.out.println(suggestion.expectedImpact());

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.strategyName()).isEqualTo(strategy.getName());
    }
}
