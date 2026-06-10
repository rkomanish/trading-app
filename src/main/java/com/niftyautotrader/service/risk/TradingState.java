package com.niftyautotrader.service.risk;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable in-memory state used by RiskEngine.
 * Reset at session start; persisted state is reconciled from DailyPnl on startup.
 */
@Component
public class TradingState {

    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);
    private final AtomicBoolean autoTradingEnabled = new AtomicBoolean(true);
    private final AtomicBoolean weeklyCircuitBreakerActive = new AtomicBoolean(false);

    private volatile LocalDate currentTradingDate = LocalDate.now();
    private final AtomicInteger tradesPlacedToday = new AtomicInteger(0);
    private volatile BigDecimal dailyRealizedLoss = BigDecimal.ZERO; // negative = loss

    /** Rolling window of last N trading days: true = losing day */
    private final LinkedList<Boolean> recentDayResults = new LinkedList<>();

    public boolean isKillSwitchActive() { return killSwitchActive.get(); }
    public void activateKillSwitch() { killSwitchActive.set(true); autoTradingEnabled.set(false); }

    public boolean isAutoTradingEnabled() { return autoTradingEnabled.get(); }
    public void disableAutoTrading() { autoTradingEnabled.set(false); }
    public void enableAutoTrading() {
        if (!killSwitchActive.get() && !weeklyCircuitBreakerActive.get()) {
            autoTradingEnabled.set(true);
        }
    }

    public boolean isWeeklyCircuitBreakerActive() { return weeklyCircuitBreakerActive.get(); }
    public void activateWeeklyCircuitBreaker() {
        weeklyCircuitBreakerActive.set(true);
        autoTradingEnabled.set(false);
    }
    public void resetWeeklyCircuitBreaker() {
        weeklyCircuitBreakerActive.set(false);
        // auto-trading re-enable requires explicit call
    }

    public int getTradesPlacedToday() { return tradesPlacedToday.get(); }
    public int incrementTradesPlacedToday() { return tradesPlacedToday.incrementAndGet(); }
    public void resetDailyCounters(LocalDate newDate) {
        currentTradingDate = newDate;
        tradesPlacedToday.set(0);
        dailyRealizedLoss = BigDecimal.ZERO;
    }

    public LocalDate getCurrentTradingDate() { return currentTradingDate; }

    public synchronized BigDecimal getDailyRealizedLoss() { return dailyRealizedLoss; }
    public synchronized void addToDailyLoss(BigDecimal amount) {
        // amount is negative for a loss, positive for a gain
        dailyRealizedLoss = dailyRealizedLoss.add(amount);
    }

    public synchronized void recordDayResult(boolean wasLosingDay, int maxWindowSize) {
        recentDayResults.addLast(wasLosingDay);
        if (recentDayResults.size() > maxWindowSize) {
            recentDayResults.removeFirst();
        }
    }

    public synchronized long countRecentLosingDays() {
        return recentDayResults.stream().filter(b -> b).count();
    }

    public synchronized int recentDaysWindowSize() {
        return recentDayResults.size();
    }
}
