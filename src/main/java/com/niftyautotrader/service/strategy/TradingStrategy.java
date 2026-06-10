package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Signal;

import java.util.Optional;

/**
 * Contract for all trading strategies.
 * Implementations are auto-discovered by Spring and must be deterministic.
 * An LLM must never implement this interface.
 */
public interface TradingStrategy {

    /** Unique name used in logs and the Signal entity */
    String getName();

    /**
     * Evaluate the current market context and return a signal if conditions are met.
     * Return Optional.empty() when no trade setup is present — never return null.
     */
    Optional<Signal> evaluate(MarketContext ctx);

    /** Whether this strategy is currently enabled (can be toggled in config/DB) */
    default boolean isEnabled() { return true; }
}
