package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;

@Entity
@Table(name = "backtest_runs")
public class BacktestRun {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false)
    private String strategyName;

    @Column(nullable = false)
    private String symbol = "NIFTY";

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "trading_days")
    private int tradingDays;

    @Column(name = "total_trades")
    private int totalTrades;

    @Column(name = "winning_trades")
    private int winningTrades;

    @Column(name = "losing_trades")
    private int losingTrades;

    @Column(name = "win_rate")
    private double winRate;

    @Column(name = "total_gross_pnl")
    private BigDecimal totalGrossPnl = BigDecimal.ZERO;

    @Column(name = "total_costs")
    private BigDecimal totalCosts = BigDecimal.ZERO;

    @Column(name = "total_net_pnl")
    private BigDecimal totalNetPnl = BigDecimal.ZERO;

    @Column(name = "avg_win")
    private BigDecimal avgWin = BigDecimal.ZERO;

    @Column(name = "avg_loss")
    private BigDecimal avgLoss = BigDecimal.ZERO;

    @Column(name = "expectancy_per_trade")
    private BigDecimal expectancyPerTrade = BigDecimal.ZERO;

    @Column(name = "max_drawdown")
    private BigDecimal maxDrawdown = BigDecimal.ZERO;

    @Column(name = "profit_factor")
    private double profitFactor;

    @Column(nullable = false)
    private boolean promotable;

    @Column(columnDefinition = "TEXT")
    private String verdict;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "is_optimized", nullable = false)
    private boolean isOptimized;

    @Column(name = "optimizer_score")
    private Double optimizerScore;

    @Column(name = "run_at", nullable = false)
    private ZonedDateTime runAt = ZonedDateTime.now();

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public Long getId()                          { return id; }
    public String getStrategyName()              { return strategyName; }
    public void setStrategyName(String v)        { this.strategyName = v; }
    public String getSymbol()                    { return symbol; }
    public void setSymbol(String v)              { this.symbol = v; }
    public LocalDate getFromDate()               { return fromDate; }
    public void setFromDate(LocalDate v)         { this.fromDate = v; }
    public LocalDate getToDate()                 { return toDate; }
    public void setToDate(LocalDate v)           { this.toDate = v; }
    public int getTradingDays()                  { return tradingDays; }
    public void setTradingDays(int v)            { this.tradingDays = v; }
    public int getTotalTrades()                  { return totalTrades; }
    public void setTotalTrades(int v)            { this.totalTrades = v; }
    public int getWinningTrades()                { return winningTrades; }
    public void setWinningTrades(int v)          { this.winningTrades = v; }
    public int getLosingTrades()                 { return losingTrades; }
    public void setLosingTrades(int v)           { this.losingTrades = v; }
    public double getWinRate()                   { return winRate; }
    public void setWinRate(double v)             { this.winRate = v; }
    public BigDecimal getTotalGrossPnl()         { return totalGrossPnl; }
    public void setTotalGrossPnl(BigDecimal v)   { this.totalGrossPnl = v; }
    public BigDecimal getTotalCosts()            { return totalCosts; }
    public void setTotalCosts(BigDecimal v)      { this.totalCosts = v; }
    public BigDecimal getTotalNetPnl()           { return totalNetPnl; }
    public void setTotalNetPnl(BigDecimal v)     { this.totalNetPnl = v; }
    public BigDecimal getAvgWin()                { return avgWin; }
    public void setAvgWin(BigDecimal v)          { this.avgWin = v; }
    public BigDecimal getAvgLoss()               { return avgLoss; }
    public void setAvgLoss(BigDecimal v)         { this.avgLoss = v; }
    public BigDecimal getExpectancyPerTrade()    { return expectancyPerTrade; }
    public void setExpectancyPerTrade(BigDecimal v){ this.expectancyPerTrade = v; }
    public BigDecimal getMaxDrawdown()           { return maxDrawdown; }
    public void setMaxDrawdown(BigDecimal v)     { this.maxDrawdown = v; }
    public double getProfitFactor()              { return profitFactor; }
    public void setProfitFactor(double v)        { this.profitFactor = v; }
    public boolean isPromotable()                { return promotable; }
    public void setPromotable(boolean v)         { this.promotable = v; }
    public String getVerdict()                   { return verdict; }
    public void setVerdict(String v)             { this.verdict = v; }
    public String getParamsJson()                { return paramsJson; }
    public void setParamsJson(String v)          { this.paramsJson = v; }
    public boolean isOptimized()                 { return isOptimized; }
    public void setOptimized(boolean v)          { this.isOptimized = v; }
    public Double getOptimizerScore()            { return optimizerScore; }
    public void setOptimizerScore(Double v)      { this.optimizerScore = v; }
    public ZonedDateTime getRunAt()              { return runAt; }
    public void setRunAt(ZonedDateTime v)        { this.runAt = v; }
}
