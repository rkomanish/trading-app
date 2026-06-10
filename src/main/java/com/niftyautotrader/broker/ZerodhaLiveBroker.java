package com.niftyautotrader.broker;

import com.niftyautotrader.config.KiteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Live broker backed by Zerodha Kite Connect.
 * DISABLED by default — only active when app.trading.live-enabled=true.
 *
 * v1 status: compiles and is config-disabled. Full implementation in v2.
 * No scheduler is wired to this bean.
 */
@Service
@ConditionalOnProperty(name = "app.trading.live-enabled", havingValue = "true")
public class ZerodhaLiveBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaLiveBroker.class);

    private final KiteProperties kiteProps;
    // KiteConnect instance injected when Kite OAuth is complete
    private volatile String accessToken;

    public ZerodhaLiveBroker(KiteProperties kiteProps) {
        this.kiteProps = kiteProps;
        log.warn("╔══════════════════════════════════════════════════════╗");
        log.warn("║  LIVE TRADING ENABLED — real money orders will fire  ║");
        log.warn("╚══════════════════════════════════════════════════════╝");
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    @Override
    public OrderResult placeOrder(OrderRequest request) {
        // TODO v2: call KiteConnect.placeOrder()
        throw new UnsupportedOperationException("Live broker not yet implemented — v2");
    }

    @Override
    public boolean cancelOrder(String brokerOrderId) {
        throw new UnsupportedOperationException("Live broker not yet implemented — v2");
    }

    @Override
    public BigDecimal getLastPrice(String symbol) {
        throw new UnsupportedOperationException("Live broker not yet implemented — v2");
    }

    @Override
    public Map<String, Integer> getOpenPositions() {
        throw new UnsupportedOperationException("Live broker not yet implemented — v2");
    }

    @Override
    public void cancelAllAndFlatten() {
        log.error("LIVE KILL SWITCH — cancelAllAndFlatten not yet implemented. MANUAL ACTION REQUIRED.");
        throw new UnsupportedOperationException("Live broker not yet implemented — v2");
    }

    @Override
    public boolean isPaper() { return false; }

    @Override
    public String getName() { return "ZerodhaLiveBroker"; }

    @Override
    public Map<String, BigDecimal> getLastPrices(List<String> symbols) {
        throw new UnsupportedOperationException("Live broker not yet implemented — v2");
    }
}
