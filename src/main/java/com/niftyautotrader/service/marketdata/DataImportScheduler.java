package com.niftyautotrader.service.marketdata;

import com.niftyautotrader.config.DataImportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automatically imports historical candles from Yahoo on a daily schedule so the
 * database accumulates 1-minute history over time.
 *
 * Yahoo only serves the last ~7 days of 1m data per request, so a one-off import
 * gives a short window. Running this every trading day means each day's fresh 1m
 * candles are saved permanently — after a few weeks you have a deep 1m archive for
 * replay and backtesting. Duplicates are skipped by YahooFinanceService.
 *
 * Disable with DATA_IMPORT_ENABLED=false.
 */
@Component
public class DataImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataImportScheduler.class);

    private final YahooFinanceService yahooService;
    private final DataImportProperties props;

    public DataImportScheduler(YahooFinanceService yahooService, DataImportProperties props) {
        this.yahooService = yahooService;
        this.props = props;
        log.info("DataImportScheduler enabled={} cron='{}' intervals={}",
            props.isEnabled(), props.getCron(),
            props.getIntervals().stream().map(DataImportProperties.IntervalSpec::getInterval).toList());
    }

    /** Runs on the configured cron (default: weekdays 16:00 IST, after market close). */
    @Scheduled(cron = "${app.data-import.cron:0 0 16 * * MON-FRI}", zone = "Asia/Kolkata")
    public void importDaily() {
        if (!props.isEnabled()) {
            log.debug("Daily data import skipped (disabled)");
            return;
        }
        runImport();
    }

    /** Shared import routine — fetches each configured interval and stores new candles. */
    public void runImport() {
        for (DataImportProperties.IntervalSpec spec : props.getIntervals()) {
            try {
                var result = yahooService.fetchAndStore(
                    props.getYahooSymbol(), props.getAppSymbol(), spec.getInterval(), spec.getRange());
                if (result.isSuccess()) {
                    log.info("Auto-import {} [{}]: {} fetched, {} saved, {} dup",
                        props.getAppSymbol(), spec.getInterval(),
                        result.fetched(), result.saved(), result.duplicates());
                } else {
                    log.warn("Auto-import {} [{}] failed: {}",
                        props.getAppSymbol(), spec.getInterval(), result.error());
                }
            } catch (Exception e) {
                log.warn("Auto-import {} [{}] threw: {}",
                    props.getAppSymbol(), spec.getInterval(), e.getMessage());
            }
        }
    }
}
