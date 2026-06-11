package com.niftyautotrader.service.marketdata;

import com.niftyautotrader.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Auto-fetches Nifty candles into the DB during market hours so the
 * StrategyEngine and PaperBroker always have fresh price data.
 *
 * Data source preference:
 *   1. Dhan API (free real-time) — used when DHAN_* credentials are set
 *   2. Yahoo Finance (15-min delayed) — fallback when Dhan is not configured
 *
 * Schedule: every 5 minutes between 09:15 and 15:30 IST, Mon–Fri,
 * skipping configured market holidays.
 *
 * The fetch pulls the current day's 5m candles (and 15m for the Supertrend
 * strategy). Existence checks prevent duplicate inserts.
 */
@Service
public class CandleFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(CandleFetchScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final String SYMBOL = "NIFTY";
    private static final String YAHOO_SYMBOL = "^NSEI";

    private final DhanMarketDataService dhanService;
    private final YahooFinanceService yahooService;
    private final TradingProperties tradingProps;

    public CandleFetchScheduler(DhanMarketDataService dhanService,
                                 YahooFinanceService yahooService,
                                 TradingProperties tradingProps) {
        this.dhanService = dhanService;
        this.yahooService = yahooService;
        this.tradingProps = tradingProps;
    }

    /**
     * Runs at second 5 of every 5th minute during market hours, Mon–Fri.
     * Cron: "5 0/5 9-15 * * MON-FRI" in Asia/Kolkata.
     * The second-5 offset gives the candle a moment to close before we pull.
     */
    @Scheduled(cron = "5 0/5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void fetchDuringMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        if (!isMarketOpen(now)) {
            return;
        }

        LocalDate today = now.toLocalDate();
        fetch("5m", today);
        fetch("15m", today);
    }

    private void fetch(String timeframe, LocalDate day) {
        try {
            if (dhanService.isConfigured()) {
                var result = dhanService.fetchAndStore(SYMBOL, timeframe, day, day);
                if (result.isSuccess()) {
                    if (result.saved() > 0) {
                        log.info("[Dhan] auto-fetch {} → {} new candles", timeframe, result.saved());
                    }
                } else {
                    log.warn("[Dhan] auto-fetch {} failed: {} — falling back to Yahoo",
                        timeframe, result.error());
                    fetchYahoo(timeframe);
                }
            } else {
                fetchYahoo(timeframe);
            }
        } catch (Exception e) {
            log.error("Candle auto-fetch error for {}: {}", timeframe, e.getMessage());
        }
    }

    private void fetchYahoo(String timeframe) {
        // Yahoo needs a range string; "1d" covers the current session
        var result = yahooService.fetchAndStore(YAHOO_SYMBOL, SYMBOL, timeframe, "1d");
        if (result.isSuccess() && result.saved() > 0) {
            log.info("[Yahoo] auto-fetch {} → {} new candles", timeframe, result.saved());
        } else if (!result.isSuccess()) {
            log.warn("[Yahoo] auto-fetch {} failed: {}", timeframe, result.error());
        }
    }

    /** Market is open Mon–Fri 09:15–15:30 IST, excluding configured holidays. */
    private boolean isMarketOpen(ZonedDateTime now) {
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        if (time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE)) {
            return false;
        }
        String todayStr = now.toLocalDate().toString();
        if (tradingProps.getMarketHolidays().contains(todayStr)) {
            log.debug("Market holiday {} — skipping candle fetch", todayStr);
            return false;
        }
        return true;
    }
}
