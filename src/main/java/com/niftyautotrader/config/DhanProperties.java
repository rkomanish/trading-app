package com.niftyautotrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dhan API credentials — sourced from environment variables only.
 * Never hardcode or commit these values.
 *
 * Dhan offers a FREE market-data API. Get credentials at:
 *   https://dhanhq.co → Profile → DhanHQ Trading APIs → Generate Access Token
 *
 * Set via environment variables:
 *   DHAN_CLIENT_ID     → app.dhan.client-id
 *   DHAN_ACCESS_TOKEN  → app.dhan.access-token
 *
 * enabled defaults to false; the scheduler falls back to Yahoo when Dhan
 * is not configured.
 */
@ConfigurationProperties(prefix = "app.dhan")
public class DhanProperties {

    private boolean enabled = false;
    private String clientId;
    private String accessToken;
    private String baseUrl = "https://api.dhan.co";

    /** Nifty 50 index on Dhan: securityId=13, segment=IDX_I, instrument=INDEX */
    private String niftySecurityId = "13";
    private String niftyExchangeSegment = "IDX_I";
    private String niftyInstrument = "INDEX";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getNiftySecurityId() { return niftySecurityId; }
    public void setNiftySecurityId(String niftySecurityId) { this.niftySecurityId = niftySecurityId; }

    public String getNiftyExchangeSegment() { return niftyExchangeSegment; }
    public void setNiftyExchangeSegment(String s) { this.niftyExchangeSegment = s; }

    public String getNiftyInstrument() { return niftyInstrument; }
    public void setNiftyInstrument(String niftyInstrument) { this.niftyInstrument = niftyInstrument; }

    /** True only when enabled AND both credentials are present. */
    public boolean isConfigured() {
        return enabled
            && clientId != null && !clientId.isBlank() && !"not-set".equals(clientId)
            && accessToken != null && !accessToken.isBlank() && !"not-set".equals(accessToken);
    }
}
