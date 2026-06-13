package com.niftyautotrader.service.backtest;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import com.niftyautotrader.service.strategy.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the grid optimizer against REAL candle data in your PostgreSQL.
 *
 * DISABLED by default — does NOT run with "mvn test".
 *
 * How to run: find your DB password in IntelliJ → Run Config → Environment Variables → DB_PASSWORD
 * Then from Terminal in project root:
 *
 *   DB_PASSWORD=yourpassword mvn test -Dtest=RealDataBacktestTest -Dspring.profiles.active=realdata
 */
@Disabled("Requires real PostgreSQL — pass DB_PASSWORD env var. See class javadoc.")
@SpringBootTest
@ActiveProfiles("realdata")
class RealDataBacktestTest {

    @Autowired CandleRepository candleRepo;
    @Autowired StrategyOptimizer optimizer;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SYMBOL = "NIFTY";

    @Test
    void backtestAllStrategiesOnRealData() {
        // Load all 5m candles we have for NIFTY
        ZonedDateTime from = ZonedDateTime.now(IST).minusDays(120);
        ZonedDateTime to   = ZonedDateTime.now(IST);
        List<Candle> candles5m = candleRepo
            .findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(SYMBOL, "5m", from, to);

        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("║  REAL DATA BACKTEST — %s 5m candles%n", SYMBOL);
        System.out.printf("║  Loaded: %d bars  |  Range: %s → %s%n",
            candles5m.size(),
            candles5m.isEmpty() ? "N/A" : candles5m.get(0).getOpenTime().toLocalDate(),
            candles5m.isEmpty() ? "N/A" : candles5m.get(candles5m.size()-1).getOpenTime().toLocalDate());
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        if (candles5m.size() < 200) {
            System.out.println("⚠ WARNING: Only " + candles5m.size() + " candles found.");
            System.out.println("  Fetch more data first via the UI: Backtest → Fetch Yahoo Data");
            System.out.println("  Symbol: ^NSEI, Timeframe: 5m, Period: 60d");
        }

        assertThat(candles5m).as("Need real candle data in DB to run this test").isNotEmpty();

        // Only test the 3 enabled strategies with positive backtest history
        List<TunableStrategy> strategies = List.of(
            new OpeningRangeBreakoutStrategy(),
            new MACDCrossoverStrategy(),
            new BollingerSqueezeStrategy(),
            new EmaCrossoverStrategy(),
            new SupertrendFollowerStrategy()
        );

        List<OptimizationResult> results = new ArrayList<>();
        int windows = candles5m.size() >= 2000 ? 4 : 2; // use fewer windows if data is limited

        for (TunableStrategy s : strategies) {
            System.out.printf("─── Optimizing: %-30s (walk-forward windows: %d) ──%n", s.getName(), windows);
            long t0 = System.currentTimeMillis();
            OptimizationResult opt = optimizer.optimize(s, candles5m, windows);
            long elapsed = System.currentTimeMillis() - t0;
            results.add(opt);

            BacktestResult best = opt.bestResult();
            if (best.totalTrades() == 0) {
                System.out.printf("  ⚠ 0 trades generated — likely not enough candle history%n%n");
                continue;
            }

            System.out.printf("  Trials: %d  Time: %dms%n", opt.totalTrials(), elapsed);
            System.out.printf("  Best params: %s%n", opt.bestParams());
            System.out.printf("  Trades: %d  Win%%: %.1f%%  PF: %.2f  Exp/trade: ₹%.0f  Net P&L: ₹%.0f%n",
                best.totalTrades(), best.winRate() * 100, best.profitFactor(),
                best.expectancyPerTrade().doubleValue(), best.totalNetPnl().doubleValue());
            System.out.printf("  Max Drawdown: ₹%.0f%n", best.maxDrawdown().doubleValue());
            System.out.printf("  Promotable: %s%n", best.promotable() ? "✓ YES" : "✗ NO");
            System.out.printf("  Verdict: %s%n", best.verdict());

            // Exit breakdown
            long targets  = best.trades().stream().filter(t -> "TARGET".equals(t.exitReason())).count();
            long slHits   = best.trades().stream().filter(t -> "STOP_LOSS".equals(t.exitReason())).count();
            long trails   = best.trades().stream().filter(t -> "TRAIL_STOP".equals(t.exitReason())).count();
            long timeExit = best.trades().stream().filter(t -> "TIME_EXIT".equals(t.exitReason())).count();
            System.out.printf("  Exits → TARGET:%d  SL:%d  TRAIL:%d  TIME:%d%n%n",
                targets, slHits, trails, timeExit);
        }

        // ── Summary table ──────────────────────────────────────────────────────
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.printf("║ %-28s %7s %7s %7s %11s %11s ║%n",
            "Strategy", "Trades", "Win%", "PF", "Exp/trade", "Net P&L");
        System.out.println("╠══════════════════════════════════════════════════════════════════════╣");
        results.stream()
            .filter(o -> o.bestResult().totalTrades() > 0)
            .sorted(Comparator.comparingDouble(o -> -o.bestResult().expectancyPerTrade().doubleValue()))
            .forEach(opt -> {
                BacktestResult r = opt.bestResult();
                System.out.printf("║ %-28s %7d %6.1f%% %7.2f %10s ₹%8.0f %s║%n",
                    opt.strategyName(), r.totalTrades(), r.winRate() * 100,
                    r.profitFactor(),
                    "₹" + String.format("%.0f", r.expectancyPerTrade().doubleValue()),
                    r.totalNetPnl().doubleValue(),
                    r.promotable() ? "✓ " : "  ");
            });
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println("  ✓ = promotable (positive expectancy in 3+/4 walk-forward windows)\n");

        // Monthly P&L breakdown for best strategy
        results.stream()
            .filter(o -> o.bestResult().totalTrades() > 0)
            .max(Comparator.comparingDouble(o -> o.bestResult().expectancyPerTrade().doubleValue()))
            .ifPresent(best -> {
                System.out.printf("── Monthly breakdown for BEST: %s ──%n", best.strategyName());
                Map<String, Double> monthly = new TreeMap<>();
                best.bestResult().trades().forEach(t -> {
                    String month = t.entryTime().withZoneSameInstant(IST).toLocalDate()
                        .withDayOfMonth(1).toString();
                    monthly.merge(month, t.netPnl().doubleValue(), Double::sum);
                });
                monthly.forEach((m, pnl) ->
                    System.out.printf("  %s : ₹%,.0f%n", m, pnl));
            });
    }
}
