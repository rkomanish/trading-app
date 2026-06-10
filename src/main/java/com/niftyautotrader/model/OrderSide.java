package com.niftyautotrader.model;

public enum OrderSide {
    BUY,   // Buy to open (long option)
    SELL   // Sell to close (exit long) — selling to OPEN is rejected by RiskEngine
}
