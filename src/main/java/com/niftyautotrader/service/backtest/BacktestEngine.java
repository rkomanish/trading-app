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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Walk-forward backtesting engine.
 *
 * Algorithm:
 *   1. Split candle history into N equal windows
 *   2. For each window: run strategy, simulate fills, compute net P&L after costs
 *   3. Aggregate results: expectancy, drawdown, profit factor, equity curve
 *   4. Verdict: promotable only if expectancy > 0 after costs in majority of windows
 */
@Service
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int LOT_SIZE = 75;
    private static final double SLIPPAGE = 0.0002; // 0.02% per side (~5 pts on Nifty at 24k)

    private final TradeCostCalculator costCalc;

    public BacktestEngine(TradeCostCalculator costCalc) {
        this.costCalc = costCalc;
    }

    /**
     * Run walk-forward backtest.
     *
     * @param strategy  the strategy to test
     * @param allCandles5m  historical 5m candles (chronological)
     * @param windowCount  number of walk-forward windows (default 4)
     */
    public BacktestResult runWalkForward(TradingStrategy strategy,
                                          List<Candle> allCandles5m,
                                          int windowCount) {
        if (allCandles5m.size() < 100) {
            return emptyResult(strategy.getName(), "Insufficient data (< 100 candles)");
        }

        int windowSize = allCandles5m.size() / windowCount;
        List<BigDecimal> allWindowExpectancies = new ArrayList<>();
        List<BacktestTrade> allTrades = new ArrayList<>();
        List<BigDecimal> equityCurve = new ArrayList<>();
        equityCurve.add(BigDecimal.ZERO);

        for (int w = 0; w < windowCount; w++) {
            int start = w * windowSize;
            int end = Math.min(start + windowSize, allCandles5m.size());
            List<Candle> window = allCandles5m.subList(start, end);

            List<BacktestTrade> windowTrades = simulateWindow(strategy, window);
            allTrades.addAll(windowTrades);

            BigDecimal windowPnl = windowTrades.stream()
                .map(BacktestTrade::netPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal windowExp = windowTrades.isEmpty() ? BigDecimal.ZERO :
                windowPnl.divide(BigDecimal.valueOf(windowTrades.size()), 2, RoundingMode.HALF_UP);
            allWindowExpectancies.add(windowExp);

            // Build equity curve
            BigDecimal running = equityCurve.get(equityCurve.size() - 1);
            for (BacktestTrade t : windowTrades) {
                running = running.add(t.netPnl());
                equityCurve.add(running);
            }
        }

        return computeResult(strategy.getName(), allTrades, equityCurve, allWindowExpectancies);
    }

    private List<BacktestTrade> simulateWindow(TradingStrategy strategy, List<Candle> candles) {
        List<BacktestTrade> trades = new ArrayList<>();
        int warmup = 40; // candles needed for indicators
        java.time.LocalDate lastTradeDate = null; // limit to one trade per day per strategy
        int lastTradeBar = -1;

        for (int i = warmup; i < candles.size() - 1; i++) {
            // Enforce minimum 30-bar (~2.5 hour) gap between trades to avoid overtrading
            if (lastTradeBar >= 0 && (i - lastTradeBar) < 30) continue;

            List<Candle> slice = candles.subList(0, i + 1);
            MarketContext ctx = buildBacktestContext(slice);
            if (ctx == null) continue;

            // ORB-style strategies: only one signal per calendar day
            java.time.LocalDate candleDate = candles.get(i).getOpenTime()
                .withZoneSameInstant(IST).toLocalDate();
            if (strategy.getName().contains("BREAKOUT") && candleDate.equals(lastTradeDate)) continue;

            try {
                var signalOpt = strategy.evaluate(ctx);
                if (signalOpt.isEmpty()) continue;
                var signal = signalOpt.get();
                lastTradeDate = candleDate;

                // Simulate fill at next bar open
                Candle nextBar = candles.get(i + 1);
                double entry = nextBar.getOpen().doubleValue() * (1 + SLIPPAGE);

                // Find exit: use SL or target, whichever hits first
                double sl = signal.getSuggestedStopLoss() != null
                    ? signal.getSuggestedStopLoss().doubleValue() : entry * 0.7;
                double target = signal.getSuggestedTarget() != null
                    ? signal.getSuggestedTarget().doubleValue() : entry * 1.3;

                boolean isBull = signal.getDirection().name().contains("CE");
                double exitPrice = candles.get(Math.min(i + 40, candles.size() - 1))
                    .getClose().doubleValue();
                boolean isWin = false;

                for (int j = i + 1; j < Math.min(i + 20, candles.size()); j++) {
                    Candle bar = candles.get(j);
                    if (isBull) {
                        if (bar.getLow().doubleValue() <= sl) {
                            exitPrice = sl * (1 - SLIPPAGE);
                            isWin = false;
                            break;
                        }
                        if (bar.getHigh().doubleValue() >= target) {
                            exitPrice = target * (1 - SLIPPAGE);
                            isWin = true;
                            break;
                        }
                    } else { // LONG_PE — profit when price falls
                        if (bar.getHigh().doubleValue() >= sl) {
                            exitPrice = sl * (1 + SLIPPAGE);
                            isWin = false;
                            break;
                        }
                        if (bar.getLow().doubleValue() <= target) {
                            exitPrice = target * (1 + SLIPPAGE);
                            isWin = true;
                            break;
                        }
                    }
                }

                BigDecimal entryBd = BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP);
                BigDecimal exitBd  = BigDecimal.valueOf(exitPrice).setScale(2, RoundingMode.HALF_UP);
                // For CE (long): profit = exit - entry; for PE (short): profit = entry - exit
                BigDecimal grossPnl = (isBull
                    ? exitBd.subtract(entryBd)
                    : entryBd.subtract(exitBd))
                    .multiply(BigDecimal.valueOf(LOT_SIZE));
                BigDecimal cost = costCalc.calculateRoundTripCost(entryBd, exitBd, LOT_SIZE, SLIPPAGE);
                BigDecimal netPnl = grossPnl.subtract(cost);

                trades.add(new BacktestTrade(entryBd, exitBd, grossPnl, cost, netPnl, isWin));
                lastTradeBar = i;
            } catch (Exception e) {
                // Strategy evaluation can fail on edge cases — skip and continue
            }
        }
        return trades;
    }

    private MarketContext buildBacktestContext(List<Candle> candles) {
        if (candles.size() < 30) return null;
        String symbol = candles.get(0).getSymbol();
        Candle latest = candles.get(candles.size() - 1);
        ZonedDateTime evalAt = latest.getOpenTime();

        double[] highs  = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] lows   = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();

        // VWAP resets each trading day — use only today's candles
        java.time.LocalDate today = evalAt.withZoneSameInstant(IST).toLocalDate();
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

        double atr  = IndicatorUtils.atrLast(highs, lows, closes, 14);
        double adx  = IndicatorUtils.adxLast(highs, lows, closes, 14);

        // Build synthetic 15m candles by aggregating every 3 consecutive 5m bars
        List<Candle> candles15m = build15mFromFiveM(candles);

        return new MarketContext(symbol, evalAt,
            List.of(), candles, candles15m,
            latest.getClose(), BigDecimal.valueOf(vwap), atr, adx, adx > 20);
    }

    /** Aggregate 5m candles into 15m candles (every 3 bars → 1 bar). */
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

    private BacktestResult computeResult(String name, List<BacktestTrade> trades,
                                          List<BigDecimal> equityCurve,
                                          List<BigDecimal> windowExpectancies) {
        if (trades.isEmpty()) return emptyResult(name, "No trades generated");

        // Use actual P&L sign (not just target-hit flag) for accurate win/loss metrics
        int wins = (int) trades.stream()
            .filter(t -> t.netPnl().compareTo(BigDecimal.ZERO) > 0).count();
        int losses = trades.size() - wins;
        double winRate = (double) wins / trades.size();

        BigDecimal totalGross = trades.stream().map(BacktestTrade::grossPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCosts = trades.stream().map(BacktestTrade::cost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalNet = trades.stream().map(BacktestTrade::netPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossWins = trades.stream()
            .filter(t -> t.netPnl().compareTo(BigDecimal.ZERO) > 0)
            .map(BacktestTrade::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLosses = trades.stream()
            .filter(t -> t.netPnl().compareTo(BigDecimal.ZERO) <= 0)
            .map(BacktestTrade::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add).abs();

        BigDecimal avgWin = wins == 0 ? BigDecimal.ZERO :
            grossWins.divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP);
        BigDecimal avgLoss = losses == 0 ? BigDecimal.ZERO :
            grossLosses.divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP).negate();

        BigDecimal expectancy = totalNet.divide(BigDecimal.valueOf(trades.size()), 2, RoundingMode.HALF_UP);

        // Max drawdown
        BigDecimal peak = BigDecimal.ZERO, maxDD = BigDecimal.ZERO;
        for (BigDecimal equity : equityCurve) {
            if (equity.compareTo(peak) > 0) peak = equity;
            BigDecimal dd = peak.subtract(equity);
            if (dd.compareTo(maxDD) > 0) maxDD = dd;
        }

        double profitFactor = grossLosses.compareTo(BigDecimal.ZERO) == 0 ? 999
            : grossWins.divide(grossLosses, 4, RoundingMode.HALF_UP).doubleValue();

        // Promotable: positive expectancy after costs AND majority of windows positive
        long positiveWindows = windowExpectancies.stream()
            .filter(e -> e.compareTo(BigDecimal.ZERO) > 0).count();
        boolean promotable = expectancy.compareTo(BigDecimal.ZERO) > 0
            && positiveWindows > windowExpectancies.size() / 2;

        String verdict = promotable
            ? String.format("PROMOTABLE — expectancy ₹%.2f/trade, %d/%d windows positive",
                expectancy, positiveWindows, windowExpectancies.size())
            : String.format("NOT PROMOTABLE — expectancy ₹%.2f/trade, %d/%d windows positive",
                expectancy, positiveWindows, windowExpectancies.size());

        return new BacktestResult(name, trades.size(), wins, losses, winRate,
            totalGross, totalCosts, totalNet, avgWin, avgLoss,
            expectancy, maxDD, profitFactor, equityCurve, promotable, verdict);
    }

    private BacktestResult emptyResult(String name, String reason) {
        return new BacktestResult(name, 0, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
            List.of(BigDecimal.ZERO), false, "NOT PROMOTABLE — " + reason);
    }

    record BacktestTrade(BigDecimal entry, BigDecimal exit,
                         BigDecimal grossPnl, BigDecimal cost,
                         BigDecimal netPnl, boolean isWin) {}
}
