package com.niftyautotrader.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "app.risk")
@Validated
public class RiskProperties {

    /** Absolute hard ceiling in code — config cannot exceed this */
    public static final int HARD_MAX_LOTS = 2;
    public static final int HARD_MAX_TRADES_PER_DAY = 5;

    /** Nifty lot size as per SEBI/NSE */
    public static final int NIFTY_LOT_SIZE = 75;

    @Min(1) @Max(2)
    private int maxLotsPerPosition = 1;

    @Min(1) @Max(5)
    private int maxTradesPerDay = 3;

    @NotNull
    @DecimalMin("500.0")
    private BigDecimal maxDailyLoss = new BigDecimal("2000.00");

    /** Number of losing days in a rolling week before weekly circuit breaker fires */
    @Min(2) @Max(5)
    private int weeklyCircuitBreakerLossDays = 3;

    /** Entry window start — no new entries before this time (HH:mm IST) */
    private String entryWindowStart = "09:30";

    /** Entry window end — no new entries after this time (HH:mm IST) */
    private String entryWindowEnd = "15:00";

    /** Force square-off at this time (HH:mm IST) */
    private String squareOffTime = "15:15";

    /** ATR multiplier for stop-loss calculation */
    @DecimalMin("1.0")
    private BigDecimal atrStopMultiplier = new BigDecimal("1.5");

    /** Fixed stop-loss percentage if ATR unavailable */
    @DecimalMin("1.0")
    private BigDecimal fixedStopPercent = new BigDecimal("30.0");

    public int getMaxLotsPerPosition() {
        return Math.min(maxLotsPerPosition, HARD_MAX_LOTS);
    }

    public void setMaxLotsPerPosition(int maxLotsPerPosition) {
        if (maxLotsPerPosition > HARD_MAX_LOTS) {
            throw new IllegalArgumentException(
                "maxLotsPerPosition cannot exceed hard ceiling of " + HARD_MAX_LOTS);
        }
        this.maxLotsPerPosition = maxLotsPerPosition;
    }

    public int getMaxTradesPerDay() {
        return Math.min(maxTradesPerDay, HARD_MAX_TRADES_PER_DAY);
    }

    public void setMaxTradesPerDay(int maxTradesPerDay) {
        if (maxTradesPerDay > HARD_MAX_TRADES_PER_DAY) {
            throw new IllegalArgumentException(
                "maxTradesPerDay cannot exceed hard ceiling of " + HARD_MAX_TRADES_PER_DAY);
        }
        this.maxTradesPerDay = maxTradesPerDay;
    }

    public BigDecimal getMaxDailyLoss() { return maxDailyLoss; }
    public void setMaxDailyLoss(BigDecimal maxDailyLoss) { this.maxDailyLoss = maxDailyLoss; }

    public int getWeeklyCircuitBreakerLossDays() { return weeklyCircuitBreakerLossDays; }
    public void setWeeklyCircuitBreakerLossDays(int v) { this.weeklyCircuitBreakerLossDays = v; }

    public String getEntryWindowStart() { return entryWindowStart; }
    public void setEntryWindowStart(String entryWindowStart) { this.entryWindowStart = entryWindowStart; }

    public String getEntryWindowEnd() { return entryWindowEnd; }
    public void setEntryWindowEnd(String entryWindowEnd) { this.entryWindowEnd = entryWindowEnd; }

    public String getSquareOffTime() { return squareOffTime; }
    public void setSquareOffTime(String squareOffTime) { this.squareOffTime = squareOffTime; }

    public BigDecimal getAtrStopMultiplier() { return atrStopMultiplier; }
    public void setAtrStopMultiplier(BigDecimal atrStopMultiplier) { this.atrStopMultiplier = atrStopMultiplier; }

    public BigDecimal getFixedStopPercent() { return fixedStopPercent; }
    public void setFixedStopPercent(BigDecimal fixedStopPercent) { this.fixedStopPercent = fixedStopPercent; }
}
