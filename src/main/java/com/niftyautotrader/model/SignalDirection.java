package com.niftyautotrader.model;

public enum SignalDirection {
    LONG_CE,    // Bullish — buy Call option
    LONG_PE,    // Bearish — buy Put option
    EXIT,       // Exit existing position
    NEUTRAL     // No action
}
