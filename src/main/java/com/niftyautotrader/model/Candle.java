package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Entity
@Table(name = "candles",
       indexes = {
           @Index(name = "idx_candle_symbol_tf_time", columnList = "symbol,timeframe,open_time")
       })
public class Candle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false, length = 5)
    private String timeframe; // "1m", "5m", "15m"

    @Column(name = "open_time", nullable = false)
    private ZonedDateTime openTime;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal open;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal high;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal low;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal close;

    @Column(nullable = false)
    private long volume;

    // Getters & setters

    public Long getId() { return id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public ZonedDateTime getOpenTime() { return openTime; }
    public void setOpenTime(ZonedDateTime openTime) { this.openTime = openTime; }

    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }

    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }

    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }

    public BigDecimal getClose() { return close; }
    public void setClose(BigDecimal close) { this.close = close; }

    public long getVolume() { return volume; }
    public void setVolume(long volume) { this.volume = volume; }
}
