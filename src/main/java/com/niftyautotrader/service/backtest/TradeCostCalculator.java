package com.niftyautotrader.service.backtest;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * NSE index futures round-trip cost calculator (used in backtesting).
 *
 * The backtest engine uses Nifty spot/futures price as the entry/exit proxy.
 * Rates are for Nifty 50 index futures (FY2024-25 NSE schedule):
 *   - Brokerage:        ₹20 flat per leg (most discount brokers)
 *   - STT:              0.01% on sell-side value
 *   - Exchange charge:  0.00188% per side
 *   - GST:              18% on (brokerage + exchange charge)
 *   - SEBI fee:         ₹10 per crore of turnover
 *   - Stamp duty:       0.00015% on buy side
 *
 * Slippage is NOT added here — the engine already applies slippage directly
 * to entry/exit prices before calling this method.
 */
@Component
public class TradeCostCalculator {

    private static final BigDecimal BROKERAGE_PER_LEG    = new BigDecimal("20.00");
    private static final BigDecimal STT_RATE             = new BigDecimal("0.0001");    // 0.01% sell side
    private static final BigDecimal EXCHANGE_RATE        = new BigDecimal("0.0000188"); // 0.00188% per side
    private static final BigDecimal GST_RATE             = new BigDecimal("0.18");
    private static final BigDecimal SEBI_FEE_PER_CRORE  = new BigDecimal("10.00");
    private static final BigDecimal CRORE                = new BigDecimal("10000000");
    private static final BigDecimal STAMP_DUTY_RATE      = new BigDecimal("0.0000015"); // 0.00015% buy side

    /**
     * @param entryPrice  price at entry (Nifty futures price, e.g. 24000)
     * @param exitPrice   price at exit
     * @param quantity    lot size (typically 75 for Nifty)
     * @param slippage    ignored — slippage already in prices
     */
    public BigDecimal calculateRoundTripCost(BigDecimal entryPrice, BigDecimal exitPrice,
                                              int quantity, double slippage) {
        BigDecimal qty            = BigDecimal.valueOf(quantity);
        BigDecimal entryTurnover  = entryPrice.multiply(qty);
        BigDecimal exitTurnover   = exitPrice.multiply(qty);
        BigDecimal totalTurnover  = entryTurnover.add(exitTurnover);

        BigDecimal brokerage      = BROKERAGE_PER_LEG.multiply(BigDecimal.TWO);
        BigDecimal stt            = exitTurnover.multiply(STT_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal exchEntry      = entryTurnover.multiply(EXCHANGE_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal exchExit       = exitTurnover.multiply(EXCHANGE_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gst            = brokerage.add(exchEntry).add(exchExit)
                                        .multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sebiFee        = totalTurnover.multiply(SEBI_FEE_PER_CRORE)
                                        .divide(CRORE, 2, RoundingMode.HALF_UP);
        BigDecimal stampDuty      = entryTurnover.multiply(STAMP_DUTY_RATE).setScale(2, RoundingMode.HALF_UP);

        return brokerage.add(stt).add(exchEntry).add(exchExit)
                        .add(gst).add(sebiFee).add(stampDuty)
                        .setScale(2, RoundingMode.HALF_UP);
    }
}
