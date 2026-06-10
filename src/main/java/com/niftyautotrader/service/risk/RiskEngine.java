package com.niftyautotrader.service.risk;

import com.niftyautotrader.broker.OrderRequest;
import com.niftyautotrader.config.RiskProperties;
import com.niftyautotrader.model.OrderSide;
import com.niftyautotrader.model.RiskEvent;
import com.niftyautotrader.repository.RiskEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * THE single gateway every order must pass through.
 * No other code may place an order without calling validate() first.
 *
 * Rules enforced:
 *  1. Kill switch — hard block on everything
 *  2. Weekly circuit breaker
 *  3. Auto-trading enabled check
 *  4. Option sell-to-open forbidden
 *  5. Trading window (time-based)
 *  6. Max lots per position (with hard ceiling)
 *  7. Max trades per day (with hard ceiling)
 *  8. Daily loss limit
 */
@Service
public final class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final RiskProperties riskProps;
    private final TradingState state;
    private final RiskEventRepository riskEventRepo;

    public RiskEngine(RiskProperties riskProps,
                      TradingState state,
                      RiskEventRepository riskEventRepo) {
        this.riskProps = riskProps;
        this.state = state;
        this.riskEventRepo = riskEventRepo;
    }

    /**
     * Validate an order request against all risk rules.
     * NEVER bypass this method. NEVER add a flag to skip validation.
     *
     * @param request the order to validate
     * @return RiskValidationResult — check isApproved() before placing
     */
    public RiskValidationResult validate(OrderRequest request) {
        List<RiskViolation> violations = new ArrayList<>();

        checkKillSwitch(violations);
        if (!violations.isEmpty()) {
            return persist(RiskValidationResult.rejected(violations), request);
        }

        checkWeeklyCircuitBreaker(violations);
        checkAutoTradingEnabled(violations);
        if (!violations.isEmpty()) {
            return persist(RiskValidationResult.rejected(violations), request);
        }

        // Closing orders (sell to exit) always allowed if not kill-switched
        if (request.isClosingOrder()) {
            return RiskValidationResult.approved();
        }

        checkOptionSellToOpen(request, violations);
        checkTradingWindow(violations);
        checkMaxLots(request, violations);
        checkMaxTradesPerDay(violations);
        checkDailyLossLimit(violations);

        if (violations.isEmpty()) {
            log.info("RiskEngine APPROVED order: symbol={} side={} qty={} strategy={}",
                request.getSymbol(), request.getSide(), request.getQuantity(), request.getStrategyName());
            return RiskValidationResult.approved();
        }

        return persist(RiskValidationResult.rejected(violations), request);
    }

    private void checkKillSwitch(List<RiskViolation> violations) {
        if (state.isKillSwitchActive()) {
            violations.add(new RiskViolation(
                RiskEvent.RiskEventType.KILL_SWITCH_ACTIVATED,
                "Kill switch is active — all trading halted"));
        }
    }

    private void checkWeeklyCircuitBreaker(List<RiskViolation> violations) {
        if (state.isWeeklyCircuitBreakerActive()) {
            violations.add(new RiskViolation(
                RiskEvent.RiskEventType.WEEKLY_CIRCUIT_BREAKER,
                "Weekly circuit breaker active — " +
                riskProps.getWeeklyCircuitBreakerLossDays() +
                " consecutive losing days reached. Manual re-enable required."));
        }
    }

    private void checkAutoTradingEnabled(List<RiskViolation> violations) {
        if (!state.isAutoTradingEnabled()) {
            violations.add(new RiskViolation(
                RiskEvent.RiskEventType.ORDER_REJECTED,
                "Auto-trading is disabled for today"));
        }
    }

    private void checkOptionSellToOpen(OrderRequest request, List<RiskViolation> violations) {
        if (request.getSide() == OrderSide.SELL && !request.isClosingOrder()) {
            violations.add(new RiskViolation(
                RiskEvent.RiskEventType.OPTION_SELL_FORBIDDEN,
                "Option selling to open a position is FORBIDDEN in v1. " +
                "Only long (buy) options are permitted."));
        }
    }

    private void checkTradingWindow(List<RiskViolation> violations) {
        LocalTime nowIst = ZonedDateTime.now(IST).toLocalTime();
        LocalTime windowStart = LocalTime.parse(riskProps.getEntryWindowStart(), TIME_FMT);
        LocalTime windowEnd = LocalTime.parse(riskProps.getEntryWindowEnd(), TIME_FMT);

        if (nowIst.isBefore(windowStart) || nowIst.isAfter(windowEnd)) {
            violations.add(new RiskViolation(
                RiskEvent.RiskEventType.TRADING_WINDOW_VIOLATION,
                String.format("Outside trading window %s–%s IST (now %s IST)",
                    riskProps.getEntryWindowStart(), riskProps.getEntryWindowEnd(),
                    nowIst.format(TIME_FMT))));
        }
    }

    private void checkMaxLots(OrderRequest request, List<RiskViolation> violations) {
        int requestedLots = request.getQuantity() / RiskProperties.NIFTY_LOT_SIZE;
        int configuredMax = riskProps.getMaxLotsPerPosition(); // already clamped to HARD_MAX_LOTS
        int effectiveMax = Math.min(configuredMax, RiskProperties.HARD_MAX_LOTS);

        if (requestedLots > effectiveMax) {
            String msg = String.format(
                "Requested %d lots exceeds max %d lots per position (hard ceiling %d)",
                requestedLots, configuredMax, RiskProperties.HARD_MAX_LOTS);
            if (requestedLots > RiskProperties.HARD_MAX_LOTS) {
                log.warn("HARD CEILING enforced: requested {} lots, hard max is {}",
                    requestedLots, RiskProperties.HARD_MAX_LOTS);
                violations.add(new RiskViolation(RiskEvent.RiskEventType.HARD_CEILING_ENFORCED, msg));
            } else {
                violations.add(new RiskViolation(RiskEvent.RiskEventType.MAX_LOTS_EXCEEDED, msg));
            }
        }
    }

    private void checkMaxTradesPerDay(List<RiskViolation> violations) {
        int tradesUsed = state.getTradesPlacedToday();
        int configuredMax = riskProps.getMaxTradesPerDay(); // already clamped to HARD_MAX_TRADES_PER_DAY
        int effectiveMax = Math.min(configuredMax, RiskProperties.HARD_MAX_TRADES_PER_DAY);

        if (tradesUsed >= effectiveMax) {
            violations.add(new RiskViolation(
                RiskEvent.RiskEventType.MAX_TRADES_EXCEEDED,
                String.format("Daily trade limit reached: %d/%d trades used today",
                    tradesUsed, effectiveMax)));
        }
    }

    private void checkDailyLossLimit(List<RiskViolation> violations) {
        BigDecimal netPnl = state.getDailyRealizedLoss();
        BigDecimal lossThreshold = riskProps.getMaxDailyLoss().negate(); // negative threshold

        if (netPnl.compareTo(lossThreshold) <= 0) {
            violations.add(new RiskViolation(
                RiskEvent.RiskEventType.DAILY_LOSS_LIMIT_BREACH,
                String.format("Daily loss limit breached: P&L=₹%.2f, limit=₹%.2f",
                    netPnl, riskProps.getMaxDailyLoss())));
        }
    }

    private RiskValidationResult persist(RiskValidationResult result, OrderRequest request) {
        result.getViolations().forEach(v -> {
            log.warn("RiskEngine REJECTED order: symbol={} side={} rule={} reason={}",
                request.getSymbol(), request.getSide(), v.type(), v.message());
            RiskEvent event = new RiskEvent();
            event.setOccurredAt(ZonedDateTime.now(IST));
            event.setEventType(v.type());
            event.setDescription(v.message());
            event.setSymbol(request.getSymbol());
            riskEventRepo.save(event);
        });
        return result;
    }

    /**
     * Record that a trade was approved — increments the daily counter.
     * Must be called by OrderExecutionService after a BUY (opening) order is submitted.
     */
    public void recordTradeApproved() {
        state.incrementTradesPlacedToday();
    }

    /**
     * Record realized P&L for the daily loss tracking.
     * Positive = gain, negative = loss.
     */
    public void recordRealizedPnl(BigDecimal pnl) {
        state.addToDailyLoss(pnl);
        BigDecimal netPnl = state.getDailyRealizedLoss();
        BigDecimal lossThreshold = riskProps.getMaxDailyLoss().negate();

        if (netPnl.compareTo(lossThreshold) <= 0) {
            log.error("DAILY LOSS LIMIT BREACHED: net P&L=₹{} limit=₹{}",
                netPnl, riskProps.getMaxDailyLoss());
            state.disableAutoTrading();
            persistEvent(RiskEvent.RiskEventType.DAILY_LOSS_LIMIT_BREACH,
                String.format("Daily loss ₹%.2f breached limit ₹%.2f — auto-trading disabled",
                    netPnl.abs(), riskProps.getMaxDailyLoss()), null);
        }
    }

    /**
     * Called at end of day to record whether it was a losing day and
     * check the weekly circuit breaker.
     */
    public void recordDayEnd(boolean wasLosingDay) {
        int windowSize = riskProps.getWeeklyCircuitBreakerLossDays() + 2; // rolling window slightly larger
        state.recordDayResult(wasLosingDay, windowSize);

        long losingDays = state.countRecentLosingDays();
        if (losingDays >= riskProps.getWeeklyCircuitBreakerLossDays()) {
            log.error("WEEKLY CIRCUIT BREAKER TRIGGERED: {} losing days in rolling window", losingDays);
            state.activateWeeklyCircuitBreaker();
            persistEvent(RiskEvent.RiskEventType.WEEKLY_CIRCUIT_BREAKER,
                String.format("%d losing days in rolling window — system paused until manually re-enabled",
                    losingDays), null);
        }
    }

    /** Activate the kill switch from external trigger (dashboard button / API). */
    public void activateKillSwitch(String reason) {
        log.error("KILL SWITCH ACTIVATED: {}", reason);
        state.activateKillSwitch();
        persistEvent(RiskEvent.RiskEventType.KILL_SWITCH_ACTIVATED,
            "Kill switch activated: " + reason, null);
    }

    private void persistEvent(RiskEvent.RiskEventType type, String description, String symbol) {
        RiskEvent event = new RiskEvent();
        event.setOccurredAt(ZonedDateTime.now(IST));
        event.setEventType(type);
        event.setDescription(description);
        event.setSymbol(symbol);
        riskEventRepo.save(event);
    }

    public TradingState getState() { return state; }
}
