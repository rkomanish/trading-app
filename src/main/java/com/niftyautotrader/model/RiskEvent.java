package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

@Entity
@Table(name = "risk_events")
public class RiskEvent {

    public enum RiskEventType {
        ORDER_REJECTED,
        DAILY_LOSS_LIMIT_BREACH,
        WEEKLY_CIRCUIT_BREAKER,
        TRADING_WINDOW_VIOLATION,
        OPTION_SELL_FORBIDDEN,
        MAX_LOTS_EXCEEDED,
        MAX_TRADES_EXCEEDED,
        KILL_SWITCH_ACTIVATED,
        STOP_LOSS_ORDER_FAILED,
        HARD_CEILING_ENFORCED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private ZonedDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskEventType eventType;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(length = 50)
    private String symbol;

    public Long getId() { return id; }

    public ZonedDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(ZonedDateTime occurredAt) { this.occurredAt = occurredAt; }

    public RiskEventType getEventType() { return eventType; }
    public void setEventType(RiskEventType eventType) { this.eventType = eventType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
}
