package com.niftyautotrader.model;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

@Entity
@Table(name = "sentiment_snapshots")
public class SentimentSnapshot {

    public enum Sentiment { BULLISH, BEARISH, NEUTRAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private ZonedDateTime capturedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sentiment sentiment;

    @Column(nullable = false)
    private double confidence;

    @Column(length = 2000)
    private String keyRisks;

    @Column(length = 2000)
    private String reasoning;

    @Column(length = 5000)
    private String rawHeadlines;

    /** true if LLM returned an error / unparseable response — used NEUTRAL fallback */
    private boolean fallback;

    // Getters & setters

    public Long getId() { return id; }

    public ZonedDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(ZonedDateTime capturedAt) { this.capturedAt = capturedAt; }

    public Sentiment getSentiment() { return sentiment; }
    public void setSentiment(Sentiment sentiment) { this.sentiment = sentiment; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getKeyRisks() { return keyRisks; }
    public void setKeyRisks(String keyRisks) { this.keyRisks = keyRisks; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getRawHeadlines() { return rawHeadlines; }
    public void setRawHeadlines(String rawHeadlines) { this.rawHeadlines = rawHeadlines; }

    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
}
