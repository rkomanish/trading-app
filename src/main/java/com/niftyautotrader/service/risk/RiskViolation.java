package com.niftyautotrader.service.risk;

import com.niftyautotrader.model.RiskEvent.RiskEventType;

public record RiskViolation(RiskEventType type, String message) {}
