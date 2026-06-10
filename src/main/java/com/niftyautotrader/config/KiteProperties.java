package com.niftyautotrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Kite Connect credentials — sourced from environment variables only.
 * Never hardcode or commit these values.
 *
 * Set via: KITE_API_KEY, KITE_API_SECRET env vars, which Spring maps to:
 *   app.kite.api-key and app.kite.api-secret
 */
@ConfigurationProperties(prefix = "app.kite")
@Validated
public class KiteProperties {

    private String apiKey;
    private String apiSecret;
    private String redirectUrl = "http://localhost:8080/auth/kite/callback";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }

    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String redirectUrl) { this.redirectUrl = redirectUrl; }
}
