package com.niftyautotrader.service.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record BacktestResult(
    String strategyName,
    int totalTrades,
    int winningTrades,
    int losingTrades,
    double winRate,
    BigDecimal totalGrossPnl,
    BigDecimal totalCosts,
    BigDecimal totalNetPnl,
    BigDecimal avgWin,
    BigDecimal avgLoss,
    BigDecimal expectancyPerTrade,
    BigDecimal maxDrawdown,
    double profitFactor,
    List<BigDecimal> equityCurve,
    boolean promotable,
    String verdict,

    // ── Per-trade detail log ────────────────────────────────
    List<TradeDetail> trades,

    // ── Daily summary (date → net P&L that day) ─────────────
    Map<LocalDate, BigDecimal> dailyPnl,

    // ── Date range of the data used ─────────────────────────
    LocalDate dataFrom,
    LocalDate dataTo,
    int tradingDaysWithData
) {}
