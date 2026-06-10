package com.niftyautotrader.service.backtest;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Realistic NSE options round-trip cost calculator.
 * Based on NSE/SEBI charges for FY2024-25.
 */
@Component
public class TradeCostCalculator {

    // Per order (one leg)
    private static final BigDecimal BROKERAGE_PER_LEG = new BigDecimal("20.00");

    // STT: 0.0625% on premium on SELL side only (options)
    private static final BigDecimal STT_RATE = new BigDecimal("0.000625");

    // Exchange transaction charge: 0.053% on premium
    private static final BigDecimal EXCHANGE_TXN_RATE = new BigDecimal("0.00053");

    // GST: 18% on (brokerage + exchange txn charges)
    private static final BigDecimal GST_RATE = new BigDecimal("0.18");

    // SEBI turnover fee: ₹10 per crore of turnover
    private static final BigDecimal SEBI_FEE_PER_CRORE = new BigDecimal("10.00");
    private static final BigDecimal CRORE = new BigDecimal("10000000");

    // Stamp duty: 0.003% on buy side
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.00003");

    /**
     * Calculate total round-trip cost for a position.
     *
     * @param entryPrice  premium paid (per unit)
     * @param exitPrice   premium received (per unit)
     * @param quantity    number of contracts (lots * lot size)
     * @param slippage    percentage slippage per side (e.g., 0.005 = 0.5%)
     * @return total cost to deduct from gross P&L
     */
    public BigDecimal calculateRoundTripCost(BigDecimal entryPrice, BigDecimal exitPrice,
                                              int quantity, double slippage) {
        BigDecimal qty = BigDecimal.valueOf(quantity);
        BigDecimal entryTurnover = entryPrice.multiply(qty);
        BigDecimal exitTurnover = exitPrice.multiply(qty);

        // Brokerage: ₹20 per leg, capped at 0.03% per leg
        BigDecimal entryBrokerage = BROKERAGE_PER_LEG.min(entryTurnover.multiply(new BigDecimal("0.0003")));
        BigDecimal exitBrokerage  = BROKERAGE_PER_LEG.min(exitTurnover.multiply(new BigDecimal("0.0003")));

        // STT: only on sell (exit) side for option buyers
        BigDecimal stt = exitTurnover.multiply(STT_RATE).setScale(2, RoundingMode.HALF_UP);

        // Exchange transaction charges (both legs)
        BigDecimal entryExchTxn = entryTurnover.multiply(EXCHANGE_TXN_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal exitExchTxn  = exitTurnover.multiply(EXCHANGE_TXN_RATE).setScale(2, RoundingMode.HALF_UP);

        // GST on (brokerage + exchange txn)
        BigDecimal taxableBase = entryBrokerage.add(exitBrokerage).add(entryExchTxn).add(exitExchTxn);
        BigDecimal gst = taxableBase.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);

        // SEBI fee on total turnover
        BigDecimal totalTurnover = entryTurnover.add(exitTurnover);
        BigDecimal sebiFee = totalTurnover.multiply(SEBI_FEE_PER_CRORE)
            .divide(CRORE, 4, RoundingMode.HALF_UP);

        // Stamp duty on entry only (buy side)
        BigDecimal stampDuty = entryTurnover.multiply(STAMP_DUTY_RATE).setScale(2, RoundingMode.HALF_UP);

        // Slippage cost
        BigDecimal entrySlip = entryPrice.multiply(BigDecimal.valueOf(slippage)).multiply(qty)
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal exitSlip  = exitPrice.multiply(BigDecimal.valueOf(slippage)).multiply(qty)
            .setScale(2, RoundingMode.HALF_UP);

        return entryBrokerage.add(exitBrokerage)
            .add(stt)
            .add(entryExchTxn).add(exitExchTxn)
            .add(gst)
            .add(sebiFee)
            .add(stampDuty)
            .add(entrySlip).add(exitSlip)
            .setScale(2, RoundingMode.HALF_UP);
    }
}
