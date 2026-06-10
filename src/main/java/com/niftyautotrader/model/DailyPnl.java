package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "daily_pnl", uniqueConstraints = @UniqueConstraint(columnNames = "trading_date"))
public class DailyPnl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalCosts = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal netPnl = BigDecimal.ZERO;

    private int totalTrades;
    private int winningTrades;
    private int losingTrades;

    /** Whether this day breached the daily loss limit */
    private boolean dailyLossLimitBreached;

    /** Whether auto-trading was halted on this day */
    private boolean autoTradingHalted;

    // Getters & setters

    public Long getId() { return id; }

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }

    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }

    public BigDecimal getTotalCosts() { return totalCosts; }
    public void setTotalCosts(BigDecimal totalCosts) { this.totalCosts = totalCosts; }

    public BigDecimal getNetPnl() { return netPnl; }
    public void setNetPnl(BigDecimal netPnl) { this.netPnl = netPnl; }

    public int getTotalTrades() { return totalTrades; }
    public void setTotalTrades(int totalTrades) { this.totalTrades = totalTrades; }

    public int getWinningTrades() { return winningTrades; }
    public void setWinningTrades(int winningTrades) { this.winningTrades = winningTrades; }

    public int getLosingTrades() { return losingTrades; }
    public void setLosingTrades(int losingTrades) { this.losingTrades = losingTrades; }

    public boolean isDailyLossLimitBreached() { return dailyLossLimitBreached; }
    public void setDailyLossLimitBreached(boolean dailyLossLimitBreached) { this.dailyLossLimitBreached = dailyLossLimitBreached; }

    public boolean isAutoTradingHalted() { return autoTradingHalted; }
    public void setAutoTradingHalted(boolean autoTradingHalted) { this.autoTradingHalted = autoTradingHalted; }
}
