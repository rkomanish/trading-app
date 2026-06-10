package com.niftyautotrader.service.auth;

import com.niftyautotrader.config.KiteProperties;
import com.niftyautotrader.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages Kite Connect OAuth flow and daily access token.
 * Tokens expire at the end of each trading day — daily re-login is required.
 */
@Service
public class KiteAuthService {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final KiteProperties kiteProps;
    private final TradingProperties tradingProps;

    private final AtomicReference<String> accessToken = new AtomicReference<>(null);
    private volatile LocalDate tokenDate = null;

    public KiteAuthService(KiteProperties kiteProps, TradingProperties tradingProps) {
        this.kiteProps = kiteProps;
        this.tradingProps = tradingProps;
    }

    /** Kite OAuth step 1: build the login URL */
    public String buildLoginUrl() {
        return "https://kite.zerodha.com/connect/login?api_key="
            + kiteProps.getApiKey() + "&v=3";
    }

    /** Kite OAuth step 2: exchange request token for access token */
    public boolean exchangeRequestToken(String requestToken) {
        if (tradingProps.isLiveEnabled()) {
            // TODO v2: call KiteConnect.generateSession(requestToken, apiSecret)
            log.warn("Live token exchange not yet implemented — v2");
            return false;
        }
        // Paper mode: store a dummy token so UI shows "connected"
        accessToken.set("PAPER-TOKEN-" + requestToken);
        tokenDate = LocalDate.now(IST);
        log.info("Paper mode: Kite auth token stored for {}", tokenDate);
        return true;
    }

    public boolean isConnected() {
        String token = accessToken.get();
        return token != null && tokenDate != null
            && tokenDate.equals(LocalDate.now(IST));
    }

    public String getAccessToken() {
        return accessToken.get();
    }

    public void clearToken() {
        accessToken.set(null);
        tokenDate = null;
    }

    public String getConnectionStatus() {
        if (!isConnected()) return "DISCONNECTED";
        return tradingProps.isLiveEnabled() ? "LIVE_CONNECTED" : "PAPER_CONNECTED";
    }
}
