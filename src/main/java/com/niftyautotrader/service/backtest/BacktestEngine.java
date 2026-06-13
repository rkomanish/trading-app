package com.niftyautotrader.service.backtest;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import com.niftyautotrader.service.strategy.MarketContext;
import com.niftyautotrader.service.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Walk-forward backtesting engine.
 *
 * Algorithm:
 *   1. Split candle history into N equal windows
 *   2. For each window: run strategy, simulate fills, compute net P&L after costs
 *   3. Aggregate results: expectancy, drawdown, profit factor, equity curve
 *   4. Verdict: promotable only if expectancy > 0 after costs in majority of windows
 *
 * Each trade is fully recorded (entry time, exit time, direction, SL, target,
 * exit reason, gross/net P&L, running cumulative P&L) for the trade log UI.
 */
@Service
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int LOT_SIZE = 75;
    private static final double SLIPPAGE = 0.0002; // 0.02% per side

    private final TradeCostCalculator costCalc;

    public BacktestEngine(TradeCostCalculator costCalc) {
        this.costCalc = costCalc;
    }

    /**
     * Run walk-forward backtest.
     *
     * @param strategy      the strategy to test
     * @param allCandles5m  historical 5m candles, chronological
     * @param windowCount   number of walk-forward windows (default 4)
     */
    public BacktestResult runWalkForward(TradingStrategy strategy,
                                          List<Candle> allCandles5m,
                                          int windowCount) {
        if (allCandles5m.size() < 100) {
            return emptyResult(strategy.getName(), allCandles5m, "Insufficient data (< 100 candles). Import at least 60 days via Backtest → Import Data.");
        }

        // Auto-reduce windows when data is short — each window needs at least 300 bars
        // (40 warmup + meaningful evaluation). With 6 days × 75 bars = 450 bars minimum per window.
        int effectiveWindows = windowCount;
        while (effectiveWindows > 1 && allCandles5m.size() / effectiveWindows < 450) {
            effectiveWindows--;
        }
        if (effectiveWindows < windowCount) {
            log.warn("Reduced walk-forward windows from {} to {} — only {} candles available (need {}+ per window).",
                windowCount, effectiveWindows, allCandles5m.size(), windowCount * 450);
        }

        int windowSize = allCandles5m.size() / effectiveWindows;
        int actualWindows = effectiveWindows;
        List<BigDecimal> allWindowExpectancies = new ArrayList<>();
        List<RawTrade> allRawTrades = new ArrayList<>();
        List<BigDecimal> equityCurve = new ArrayList<>();
        equityCurve.add(BigDecimal.ZERO);

        for (int w = 0; w < actualWindows; w++) {
            int start = w * windowSize;
            int end = Math.min(start + windowSize, allCandles5m.size());
            List<Candle> window = allCandles5m.subList(start, end);

            List<RawTrade> windowTrades = simulateWindow(strategy, window);
            allRawTrades.addAll(windowTrades);

            BigDecimal windowPnl = windowTrades.stream()
                .map(RawTrade::netPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal windowExp = windowTrades.isEmpty() ? BigDecimal.ZERO :
                windowPnl.divide(BigDecimal.valueOf(windowTrades.size()), 2, RoundingMode.HALF_UP);
            allWindowExpectancies.add(windowExp);

            BigDecimal running = equityCurve.get(equityCurve.size() - 1);
            for (RawTrade t : windowTrades) {
                running = running.add(t.netPnl());
                equityCurve.add(running);
            }
        }

        return computeResult(strategy.getName(), allRawTrades, equityCurve,
            allWindowExpectancies, allCandles5m);
    }

    // ── Internal simulation ───────────────────────────────────────────────────

    private List<RawTrade> simulateWindow(TradingStrategy strategy, List<Candle> candles) {
        List<RawTrade> trades = new ArrayList<>();
        int warmup = 40;
        LocalDate lastTradeDate = null;
        int lastTradeBar = -1;

        for (int i = warmup; i < candles.size() - 1; i++) {
            if (lastTradeBar >= 0 && (i - lastTradeBar) < 30) continue;

            List<Candle> slice = candles.subList(0, i + 1);
            MarketContext ctx = buildBacktestContext(slice);
            if (ctx == null) continue;

            LocalDate candleDate = candles.get(i).getOpenTime()
                .withZoneSameInstant(IST).toLocalDate();
            if (strategy.getName().contains("BREAKOUT") && candleDate.equals(lastTradeDate)) continue;

            try {
                var signalOpt = strategy.evaluate(ctx);
                if (signalOpt.isEmpty()) continue;
                var signal = signalOpt.get();
                lastTradeDate = candleDate;

                // Fill at next bar open
                Candle entryBar = candles.get(i + 1);
                double rawEntry = entryBar.getOpen().doubleValue();
                boolean isBull = signal.getDirection().name().contains("CE");
                double entry = rawEntry * (isBull ? (1 + SLIPPAGE) : (1 - SLIPPAGE));

                double sl = signal.getSuggestedStopLoss() != null
                    ? signal.getSuggestedStopLoss().doubleValue() : entry * 0.7;
                double target = signal.getSuggestedTarget() != null
                    ? signal.getSuggestedTarget().doubleValue() : entry * 1.3;

                // Time-exit: up to 50 bars (≈4 hours) to reach target.
                // 25 bars was cutting winners too early → PF < 1 despite 59% win rates.
                int maxBars = 50;
                int timeExitBar = Math.min(i + maxBars, candles.size() - 1);
                double exitPrice = candles.get(timeExitBar).getClose().doubleValue();
                String exitReason = "TIME_EXIT";
                ZonedDateTime exitTime = candles.get(timeExitBar).getOpenTime();

                // Trailing stop: protect profit without cutting winners too early.
                // Early trail at 0.5× risk was too tight — Nifty noise triggers it prematurely.
                // Instead: breakeven at 1×risk, then lock 0.5×risk at 1.5×risk gain.
                double trailSl       = sl;
                double initialRisk   = Math.abs(entry - sl);
                boolean breakEvenSet = false;

                for (int j = i + 1; j < Math.min(i + maxBars, candles.size()); j++) {
                    Candle bar = candles.get(j);
                    double barHigh = bar.getHigh().doubleValue();
                    double barLow  = bar.getLow().doubleValue();

                    if (isBull) {
                        double favorMove = barHigh - entry;
                        // At 1×risk gain → move SL to breakeven (protect capital)
                        if (!breakEvenSet && favorMove >= initialRisk) {
                            trailSl = entry + SLIPPAGE * entry;
                            breakEvenSet = true;
                        }
                        // At 1.5×risk gain → trail to lock in 0.5×risk profit
                        if (breakEvenSet && favorMove >= initialRisk * 1.5) {
                            double newTrail = entry + initialRisk * 0.5;
                            if (newTrail > trailSl) trailSl = newTrail;
                        }
                        if (barLow <= trailSl) {
                            exitPrice = trailSl * (1 - SLIPPAGE);
                            exitReason = breakEvenSet ? "TRAIL_STOP" : "STOP_LOSS";
                            exitTime = bar.getOpenTime();
                            break;
                        }
                        if (barHigh >= target) {
                            exitPrice = target * (1 - SLIPPAGE);
                            exitReason = "TARGET";
                            exitTime = bar.getOpenTime();
                            break;
                        }
                    } else {
                        double favorMove = entry - barLow;
                        // At 1×risk gain → move SL to breakeven (protect capital)
                        if (!breakEvenSet && favorMove >= initialRisk) {
                            trailSl = entry - SLIPPAGE * entry;
                            breakEvenSet = true;
                        }
                        // At 1.5×risk gain → trail to lock in 0.5×risk profit
                        if (breakEvenSet && favorMove >= initialRisk * 1.5) {
                            double newTrail = entry - initialRisk * 0.5;
                            if (newTrail < trailSl) trailSl = newTrail;
                        }
                        if (barHigh >= trailSl) {
                            exitPrice = trailSl * (1 + SLIPPAGE);
                            exitReason = breakEvenSet ? "TRAIL_STOP" : "STOP_LOSS";
                            exitTime = bar.getOpenTime();
                            break;
                        }
                        if (barLow <= target) {
                            exitPrice = target * (1 + SLIPPAGE);
                            exitReason = "TARGET";
                            exitTime = bar.getOpenTime();
                            break;
                        }
                    }
                }

                BigDecimal entryBd = BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP);
                BigDecimal exitBd  = BigDecimal.valueOf(exitPrice).setScale(2, RoundingMode.HALF_UP);
                BigDecimal slBd    = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
                BigDecimal tgtBd   = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

                BigDecimal grossPnl = (isBull
                    ? exitBd.subtract(entryBd)
                    : entryBd.subtract(exitBd))
                    .multiply(BigDecimal.valueOf(LOT_SIZE));
                BigDecimal cost = costCalc.calculateRoundTripCost(entryBd, exitBd, LOT_SIZE, SLIPPAGE);
                BigDecimal netPnl = grossPnl.subtract(cost);

                trades.add(new RawTrade(
                    entryBar.getOpenTime(), exitTime,
                    signal.getDirection().name(),
                    entryBd, slBd, tgtBd, exitBd,
                    exitReason, grossPnl, cost, netPnl
                ));
                lastTradeBar = i;
            } catch (Exception e) {
                // skip edge-case failures
            }
        }
        return trades;
    }

    // ── Result assembly ───────────────────────────────────────────────────────

    private BacktestResult computeResult(String name,
                                          List<RawTrade> rawTrades,
                                          List<BigDecimal> equityCurve,
                                          List<BigDecimal> windowExpectancies,
                                          List<Candle> allCandles) {
        // Data range
        LocalDate dataFrom = allCandles.isEmpty() ? null
            : allCandles.get(0).getOpenTime().withZoneSameInstant(IST).toLocalDate();
        LocalDate dataTo = allCandles.isEmpty() ? null
            : allCandles.get(allCandles.size() - 1).getOpenTime().withZoneSameInstant(IST).toLocalDate();
        long tradingDays = allCandles.stream()
            .map(c -> c.getOpenTime().withZoneSameInstant(IST).toLocalDate())
            .distinct().count();

        if (rawTrades.isEmpty()) return emptyResult(name, allCandles, "No trades generated");

        // Build public TradeDetail list with running P&L
        List<TradeDetail> details = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < rawTrades.size(); i++) {
            RawTrade r = rawTrades.get(i);
            running = running.add(r.netPnl());
            boolean win = r.netPnl().compareTo(BigDecimal.ZERO) > 0;
            details.add(new TradeDetail(
                i + 1,
                r.entryTime(), r.exitTime(),
                r.direction(),
                r.entry(), r.sl(), r.target(), r.exit(),
                r.exitReason(),
                r.grossPnl(), r.cost(), r.netPnl(),
                win, running
            ));
        }

        // Daily P&L map
        Map<LocalDate, BigDecimal> dailyPnl = new LinkedHashMap<>();
        for (RawTrade r : rawTrades) {
            LocalDate d = r.entryTime().withZoneSameInstant(IST).toLocalDate();
            dailyPnl.merge(d, r.netPnl(), BigDecimal::add);
        }
        // Sort by date
        Map<LocalDate, BigDecimal> sortedDaily = dailyPnl.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new));

        // Aggregate metrics
        int wins = (int) details.stream().filter(TradeDetail::win).count();
        int losses = details.size() - wins;
        double winRate = (double) wins / details.size();

        BigDecimal totalGross = rawTrades.stream().map(RawTrade::grossPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCosts = rawTrades.stream().map(RawTrade::cost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalNet = rawTrades.stream().map(RawTrade::netPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossWins = details.stream().filter(TradeDetail::win)
            .map(TradeDetail::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLosses = details.stream().filter(t -> !t.win())
            .map(TradeDetail::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add).abs();

        BigDecimal avgWin = wins == 0 ? BigDecimal.ZERO :
            grossWins.divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP);
        BigDecimal avgLoss = losses == 0 ? BigDecimal.ZERO :
            grossLosses.divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP).negate();

        BigDecimal expectancy = totalNet.divide(
            BigDecimal.valueOf(details.size()), 2, RoundingMode.HALF_UP);

        // Max drawdown
        BigDecimal peak = BigDecimal.ZERO, maxDD = BigDecimal.ZERO;
        for (BigDecimal eq : equityCurve) {
            if (eq.compareTo(peak) > 0) peak = eq;
            BigDecimal dd = peak.subtract(eq);
            if (dd.compareTo(maxDD) > 0) maxDD = dd;
        }

        double profitFactor = grossLosses.compareTo(BigDecimal.ZERO) == 0 ? 999
            : grossWins.divide(grossLosses, 4, RoundingMode.HALF_UP).doubleValue();

        long positiveWindows = windowExpectancies.stream()
            .filter(e -> e.compareTo(BigDecimal.ZERO) > 0).count();
        boolean promotable = expectancy.compareTo(BigDecimal.ZERO) > 0
            && positiveWindows > windowExpectancies.size() / 2;

        String verdict = promotable
            ? String.format("PROMOTABLE — expectancy ₹%.2f/trade, %d/%d windows positive",
                expectancy, positiveWindows, windowExpectancies.size())
            : String.format("NOT PROMOTABLE — expectancy ₹%.2f/trade, %d/%d windows positive",
                expectancy, positiveWindows, windowExpectancies.size());

        return new BacktestResult(
            name, details.size(), wins, losses, winRate,
            totalGross, totalCosts, totalNet, avgWin, avgLoss,
            expectancy, maxDD, profitFactor, equityCurve, promotable, verdict,
            details, sortedDaily,
            dataFrom, dataTo, (int) tradingDays
        );
    }

    private BacktestResult emptyResult(String name, List<Candle> candles, String reason) {
        LocalDate from = candles.isEmpty() ? null
            : candles.get(0).getOpenTime().withZoneSameInstant(IST).toLocalDate();
        LocalDate to = candles.isEmpty() ? null
            : candles.get(candles.size() - 1).getOpenTime().withZoneSameInstant(IST).toLocalDate();
        long days = candles.stream()
            .map(c -> c.getOpenTime().withZoneSameInstant(IST).toLocalDate())
            .distinct().count();
        return new BacktestResult(name, 0, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
            List.of(BigDecimal.ZERO), false, "NOT PROMOTABLE — " + reason,
            List.of(), Map.of(), from, to, (int) days);
    }

    // ── Context builder ───────────────────────────────────────────────────────

    private MarketContext buildBacktestContext(List<Candle> candles) {
        if (candles.size() < 30) return null;
        Candle latest = candles.get(candles.size() - 1);
        ZonedDateTime evalAt = latest.getOpenTime();

        double[] highs  = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();

        LocalDate today = evalAt.withZoneSameInstant(IST).toLocalDate();
        List<Candle> todayCandles = candles.stream()
            .filter(c -> c.getOpenTime().withZoneSameInstant(IST).toLocalDate().equals(today))
            .toList();
        double vwap;
        if (todayCandles.size() > 1) {
            double[] th = todayCandles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
            double[] tl = todayCandles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
            double[] tc = todayCandles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
            long[]   tv = todayCandles.stream().mapToLong(Candle::getVolume).toArray();
            vwap = IndicatorUtils.vwapLast(th, tl, tc, tv);
        } else {
            vwap = latest.getClose().doubleValue();
        }

        double atr = IndicatorUtils.atrLast(highs, lows, closes, 14);
        double adx = IndicatorUtils.adxLast(highs, lows, closes, 14);
        List<Candle> candles15m = build15mFromFiveM(candles);

        return new MarketContext(candles.get(0).getSymbol(), evalAt,
            List.of(), candles, candles15m,
            latest.getClose(), BigDecimal.valueOf(vwap), atr, adx, adx > 20);
    }

    private List<Candle> build15mFromFiveM(List<Candle> fiveM) {
        List<Candle> result = new ArrayList<>();
        for (int i = 0; i + 2 < fiveM.size(); i += 3) {
            Candle a = fiveM.get(i), b = fiveM.get(i + 1), c = fiveM.get(i + 2);
            Candle bar = new Candle();
            bar.setSymbol(a.getSymbol());
            bar.setTimeframe("15m");
            bar.setOpenTime(a.getOpenTime());
            bar.setOpen(a.getOpen());
            bar.setHigh(a.getHigh().max(b.getHigh()).max(c.getHigh()));
            bar.setLow(a.getLow().min(b.getLow()).min(c.getLow()));
            bar.setClose(c.getClose());
            bar.setVolume(a.getVolume() + b.getVolume() + c.getVolume());
            result.add(bar);
        }
        return result;
    }

    // ── Internal raw trade (before public TradeDetail) ────────────────────────

    record RawTrade(
        ZonedDateTime entryTime, ZonedDateTime exitTime,
        String direction,
        BigDecimal entry, BigDecimal sl, BigDecimal target, BigDecimal exit,
        String exitReason,
        BigDecimal grossPnl, BigDecimal cost, BigDecimal netPnl
    ) {}
}
