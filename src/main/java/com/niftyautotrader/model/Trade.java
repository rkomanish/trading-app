package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String strategyName;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OptionType optionType;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal entryPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal exitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal stopLossPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal targetPrice;

    private int lots;
    private int quantity; // lots * LOT_SIZE

    @Column(nullable = false)
    private ZonedDateTime entryTime;

    private ZonedDateTime exitTime;

    @Column(precision = 12, scale = 2)
    private BigDecimal realizedPnl;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalCosts; // brokerage + taxes

    /** Paper or live */
    private boolean paper;

    @Column(length = 2000)
    private String exitReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id")
    private Signal signal;

    // Getters & setters

    public Long getId() { return id; }

    public String getStrategyName() { return strategyName; }
    public void setStrategyName(String strategyName) { this.strategyName = strategyName; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public OptionType getOptionType() { return optionType; }
    public void setOptionType(OptionType optionType) { this.optionType = optionType; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }

    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(BigDecimal stopLossPrice) { this.stopLossPrice = stopLossPrice; }

    public BigDecimal getTargetPrice() { return targetPrice; }
    public void setTargetPrice(BigDecimal targetPrice) { this.targetPrice = targetPrice; }

    public int getLots() { return lots; }
    public void setLots(int lots) { this.lots = lots; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public ZonedDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(ZonedDateTime entryTime) { this.entryTime = entryTime; }

    public ZonedDateTime getExitTime() { return exitTime; }
    public void setExitTime(ZonedDateTime exitTime) { this.exitTime = exitTime; }

    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }

    public BigDecimal getTotalCosts() { return totalCosts; }
    public void setTotalCosts(BigDecimal totalCosts) { this.totalCosts = totalCosts; }

    public boolean isPaper() { return paper; }
    public void setPaper(boolean paper) { this.paper = paper; }

    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }

    public Signal getSignal() { return signal; }
    public void setSignal(Signal signal) { this.signal = signal; }
}
