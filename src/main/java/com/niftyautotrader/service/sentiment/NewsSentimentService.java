package com.niftyautotrader.service.sentiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niftyautotrader.config.SentimentProperties;
import com.niftyautotrader.model.SentimentSnapshot;
import com.niftyautotrader.repository.SentimentSnapshotRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches RSS headlines, calls Anthropic API for sentiment, stores result.
 * On any failure: falls back to NEUTRAL and logs — never throws.
 *
 * The LLM result can only veto, never originate trades.
 */
@Service
public class NewsSentimentService {

    private static final Logger log = LoggerFactory.getLogger(NewsSentimentService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SentimentProperties sentimentProps;
    private final SentimentSnapshotRepository snapshotRepo;
    private final WebClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Value("${app.anthropic.api-key:not-set}")
    private String anthropicApiKey;

    public NewsSentimentService(SentimentProperties sentimentProps,
                                 SentimentSnapshotRepository snapshotRepo,
                                 WebClient.Builder webClientBuilder,
                                 ObjectMapper objectMapper) {
        this.sentimentProps = sentimentProps;
        this.snapshotRepo = snapshotRepo;
        this.anthropicClient = webClientBuilder
            .baseUrl("https://api.anthropic.com")
            .build();
        this.objectMapper = objectMapper;
    }

    /** Every 15 minutes during market hours */
    @Scheduled(cron = "0 */15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshSentiment() {
        try {
            List<String> headlines = fetchHeadlines();
            if (headlines.isEmpty()) {
                saveFallback("No headlines fetched");
                return;
            }
            String prompt = buildPrompt(headlines);
            String rawResponse = callAnthropicApi(prompt);
            SentimentSnapshot snap = parseResponse(rawResponse, String.join("\n", headlines));
            snapshotRepo.save(snap);
            log.info("Sentiment updated: {} (conf={:.2f}) fallback={}",
                snap.getSentiment(), snap.getConfidence(), snap.isFallback());
        } catch (Exception e) {
            log.error("Sentiment refresh failed — defaulting to NEUTRAL: {}", e.getMessage());
            saveFallback(e.getMessage());
        }
    }

    private List<String> fetchHeadlines() {
        List<String> headlines = new ArrayList<>();
        SyndFeedInput input = new SyndFeedInput();
        for (String feedUrl : sentimentProps.getRssFeedUrls()) {
            try {
                SyndFeed feed = input.build(new XmlReader(new URL(feedUrl)));
                for (SyndEntry entry : feed.getEntries()) {
                    String title = entry.getTitle();
                    if (title != null && !title.isBlank()) {
                        headlines.add(title.trim());
                    }
                    if (headlines.size() >= 20) break; // cap per feed
                }
            } catch (Exception e) {
                log.warn("Failed to fetch RSS feed {}: {}", feedUrl, e.getMessage());
            }
        }
        return headlines;
    }

    private String buildPrompt(List<String> headlines) {
        return """
            You are a market sentiment analyzer for Indian equity markets (NSE Nifty 50).
            Analyze the following market headlines and return ONLY a JSON object with no markdown fences or extra text.

            Required JSON schema:
            {
              "sentiment": "BULLISH|BEARISH|NEUTRAL",
              "confidence": <number 0.0 to 1.0>,
              "keyRisks": ["risk1", "risk2"],
              "reasoning": "<one sentence>"
            }

            Headlines:
            """ + String.join("\n- ", headlines);
    }

    private String callAnthropicApi(String prompt) {
        if ("not-set".equals(anthropicApiKey)) {
            throw new IllegalStateException("ANTHROPIC_API_KEY not configured");
        }

        Map<String, Object> requestBody = Map.of(
            "model", sentimentProps.getAnthropicModel(),
            "max_tokens", sentimentProps.getMaxTokens(),
            "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        return anthropicClient.post()
            .uri("/v1/messages")
            .header("x-api-key", anthropicApiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .block();
    }

    private SentimentSnapshot parseResponse(String rawResponse, String headlines) {
        SentimentSnapshot snap = new SentimentSnapshot();
        snap.setCapturedAt(ZonedDateTime.now(IST));
        snap.setRawHeadlines(headlines.substring(0, Math.min(headlines.length(), 4000)));

        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            // Anthropic wraps content in content[0].text
            String text = root.path("content").get(0).path("text").asText();

            // Strip markdown code fences if present
            text = stripCodeFences(text).trim();

            JsonNode parsed = objectMapper.readTree(text);
            String sentimentStr = parsed.path("sentiment").asText("NEUTRAL").toUpperCase();
            double confidence = parsed.path("confidence").asDouble(0.5);
            JsonNode risks = parsed.path("keyRisks");
            String reasoning = parsed.path("reasoning").asText("");

            // Validate sentiment value
            SentimentSnapshot.Sentiment sentiment;
            try {
                sentiment = SentimentSnapshot.Sentiment.valueOf(sentimentStr);
            } catch (IllegalArgumentException e) {
                sentiment = SentimentSnapshot.Sentiment.NEUTRAL;
            }

            // Clamp confidence
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            snap.setSentiment(sentiment);
            snap.setConfidence(confidence);
            snap.setReasoning(reasoning.substring(0, Math.min(reasoning.length(), 1000)));

            if (risks.isArray()) {
                List<String> riskList = new ArrayList<>();
                risks.forEach(r -> riskList.add(r.asText()));
                snap.setKeyRisks(String.join("; ", riskList).substring(
                    0, Math.min(String.join("; ", riskList).length(), 1000)));
            }

            snap.setFallback(false);
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic response — defaulting to NEUTRAL: {}", e.getMessage());
            snap.setSentiment(SentimentSnapshot.Sentiment.NEUTRAL);
            snap.setConfidence(0.5);
            snap.setFallback(true);
            snap.setReasoning("Parse error: " + e.getMessage().substring(0, Math.min(e.getMessage().length(), 200)));
        }

        return snap;
    }

    private String stripCodeFences(String text) {
        // Remove ```json ... ``` or ``` ... ```
        Pattern p = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1);
        return text;
    }

    private void saveFallback(String reason) {
        SentimentSnapshot snap = new SentimentSnapshot();
        snap.setCapturedAt(ZonedDateTime.now(IST));
        snap.setSentiment(SentimentSnapshot.Sentiment.NEUTRAL);
        snap.setConfidence(0.5);
        snap.setFallback(true);
        snap.setReasoning("Fallback: " + (reason != null ? reason.substring(0, Math.min(reason.length(), 200)) : "unknown"));
        snapshotRepo.save(snap);
    }
}
