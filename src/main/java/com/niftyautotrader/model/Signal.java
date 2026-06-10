package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Entity
@Table(name = "signals")
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private ZonedDateTime generatedAt;

    @Column(nullable = false, length = 50)
    private String strategyName;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignalDirection direction;

    @Column(precision = 12, scale = 2)
    private BigDecimal suggestedEntry;

    @Column(precision = 12, scale = 2)
    private BigDecimal suggestedStopLoss;

    @Column(precision = 12, scale = 2)
    private BigDecimal suggestedTarget;

    private int lots;

    @Column(length = 2000)
    private String reasoning;

    /** Whether this signal was vetoed by the sentiment layer */
    private boolean vetoedBySentiment = false;

    @Column(length = 500)
    private String vetoReason;

    /** Whether this signal passed risk checks */
    private boolean riskApproved = false;

    @Column(length = 500)
    private String riskRejectionReason;

    // Getters & setters

    public Long getId() { return id; }

    public ZonedDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(ZonedDateTime generatedAt) { this.generatedAt = generatedAt; }

    public String getStrategyName() { return strategyName; }
    public void setStrategyName(String strategyName) { this.strategyName = strategyName; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public SignalDirection getDirection() { return direction; }
    public void setDirection(SignalDirection direction) { this.direction = direction; }

    public BigDecimal getSuggestedEntry() { return suggestedEntry; }
    public void setSuggestedEntry(BigDecimal suggestedEntry) { this.suggestedEntry = suggestedEntry; }

    public BigDecimal getSuggestedStopLoss() { return suggestedStopLoss; }
    public void setSuggestedStopLoss(BigDecimal suggestedStopLoss) { this.suggestedStopLoss = suggestedStopLoss; }

    public BigDecimal getSuggestedTarget() { return suggestedTarget; }
    public void setSuggestedTarget(BigDecimal suggestedTarget) { this.suggestedTarget = suggestedTarget; }

    public int getLots() { return lots; }
    public void setLots(int lots) { this.lots = lots; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public boolean isVetoedBySentiment() { return vetoedBySentiment; }
    public void setVetoedBySentiment(boolean vetoedBySentiment) { this.vetoedBySentiment = vetoedBySentiment; }

    public String getVetoReason() { return vetoReason; }
    public void setVetoReason(String vetoReason) { this.vetoReason = vetoReason; }

    public boolean isRiskApproved() { return riskApproved; }
    public void setRiskApproved(boolean riskApproved) { this.riskApproved = riskApproved; }

    public String getRiskRejectionReason() { return riskRejectionReason; }
    public void setRiskRejectionReason(String riskRejectionReason) { this.riskRejectionReason = riskRejectionReason; }
}
