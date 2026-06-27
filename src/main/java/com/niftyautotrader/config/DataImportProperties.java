package com.niftyautotrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the daily auto-import of historical candles from Yahoo.
 *
 * Yahoo serves only the last ~7 days of 1m data per request, so running this
 * every trading day lets the database accumulate a deep 1m history over time.
 *
 * Disable with DATA_IMPORT_ENABLED=false (app.data-import.enabled).
 */
@ConfigurationProperties(prefix = "app.data-import")
public class DataImportProperties {

    private boolean enabled = true;
    private String yahooSymbol = "^NSEI";
    private String appSymbol = "NIFTY";
    private String cron = "0 0 16 * * MON-FRI";
    private List<IntervalSpec> intervals = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getYahooSymbol() { return yahooSymbol; }
    public void setYahooSymbol(String yahooSymbol) { this.yahooSymbol = yahooSymbol; }

    public String getAppSymbol() { return appSymbol; }
    public void setAppSymbol(String appSymbol) { this.appSymbol = appSymbol; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public List<IntervalSpec> getIntervals() { return intervals; }
    public void setIntervals(List<IntervalSpec> intervals) { this.intervals = intervals; }

    /** One (interval, range) pair to fetch, e.g. interval=1m range=7d. */
    public static class IntervalSpec {
        private String interval;
        private String range;

        public String getInterval() { return interval; }
        public void setInterval(String interval) { this.interval = interval; }

        public String getRange() { return range; }
        public void setRange(String range) { this.range = range; }
    }
}
