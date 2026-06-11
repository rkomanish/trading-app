package com.niftyautotrader.service.backtest;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/**
 * Detailed record of a single simulated trade — exposed in BacktestResult for the UI trade log.
 */
public record TradeDetail(
    int tradeNumber,
    ZonedDateTime entryTime,
    ZonedDateTime exitTime,
    String direction,        // LONG_CE or LONG_PE
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal target,
    BigDecimal exitPrice,
    String exitReason,       // "TARGET", "STOP_LOSS", "TIME_EXIT"
    BigDecimal grossPnl,
    BigDecimal costs,
    BigDecimal netPnl,
    boolean win,
    BigDecimal runningPnl    // cumulative net P&L after this trade
) {}
