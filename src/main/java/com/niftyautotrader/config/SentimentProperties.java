package com.niftyautotrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.sentiment")
public class SentimentProperties {

    private List<String> rssFeedUrls = List.of(
        "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",
        "https://www.moneycontrol.com/rss/marketreports.xml"
    );

    /** Confidence threshold above which a BEARISH/BULLISH veto triggers */
    private double vetoConfidenceThreshold = 0.70;

    /** Anthropic model to use for sentiment */
    private String anthropicModel = "claude-haiku-4-5-20251001";

    /** Max tokens for sentiment response */
    private int maxTokens = 512;

    public List<String> getRssFeedUrls() { return rssFeedUrls; }
    public void setRssFeedUrls(List<String> rssFeedUrls) { this.rssFeedUrls = rssFeedUrls; }

    public double getVetoConfidenceThreshold() { return vetoConfidenceThreshold; }
    public void setVetoConfidenceThreshold(double vetoConfidenceThreshold) { this.vetoConfidenceThreshold = vetoConfidenceThreshold; }

    public String getAnthropicModel() { return anthropicModel; }
    public void setAnthropicModel(String anthropicModel) { this.anthropicModel = anthropicModel; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
}
