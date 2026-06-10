package com.niftyautotrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Master trading configuration.
 * live-enabled defaults to FALSE — requires explicit opt-in.
 */
@ConfigurationProperties(prefix = "app.trading")
@Validated
public class TradingProperties {

    /** MASTER TOGGLE: false = paper trading, true = real orders. Default OFF. */
    private boolean liveEnabled = false;

    /** Instrument token for Nifty 50 index (for index level) */
    private String niftyIndexToken = "256265";

    /** Instrument tokens to trade options on */
    private List<String> optionUnderlying = List.of("NIFTY");

    /** Market holidays (yyyy-MM-dd) — no trading on these dates */
    private List<String> marketHolidays = List.of();

    /** Slippage percentage per side for paper fills (default 0.5%) */
    private double paperSlippagePercent = 0.5;

    /** Brokerage per round trip in INR */
    private double brokeragePerRoundTrip = 40.0;

    public boolean isLiveEnabled() { return liveEnabled; }
    public void setLiveEnabled(boolean liveEnabled) { this.liveEnabled = liveEnabled; }

    public String getNiftyIndexToken() { return niftyIndexToken; }
    public void setNiftyIndexToken(String niftyIndexToken) { this.niftyIndexToken = niftyIndexToken; }

    public List<String> getOptionUnderlying() { return optionUnderlying; }
    public void setOptionUnderlying(List<String> optionUnderlying) { this.optionUnderlying = optionUnderlying; }

    public List<String> getMarketHolidays() { return marketHolidays; }
    public void setMarketHolidays(List<String> marketHolidays) { this.marketHolidays = marketHolidays; }

    public double getPaperSlippagePercent() { return paperSlippagePercent; }
    public void setPaperSlippagePercent(double paperSlippagePercent) { this.paperSlippagePercent = paperSlippagePercent; }

    public double getBrokeragePerRoundTrip() { return brokeragePerRoundTrip; }
    public void setBrokeragePerRoundTrip(double brokeragePerRoundTrip) { this.brokeragePerRoundTrip = brokeragePerRoundTrip; }
}
