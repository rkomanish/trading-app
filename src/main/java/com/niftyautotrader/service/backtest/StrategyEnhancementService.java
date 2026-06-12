package com.niftyautotrader.service.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Part C — sends backtest + optimization results to Claude API and gets
 * concrete strategy improvement suggestions back.
 *
 * Claude acts as a quant analyst: it reads the full performance data
 * (win/loss breakdown, which exit reasons dominate, best vs worst param combos)
 * and returns structured rule changes with reasoning.
 *
 * The suggestions are NEVER auto-applied — they are shown to the user
 * who decides whether to implement them. This maintains the security invariant:
 * "An LLM must never originate trades or directly modify strategy logic."
 */
@Service
public class StrategyEnhancementService {

    private static final Logger log = LoggerFactory.getLogger(StrategyEnhancementService.class);

    private final WebClient anthropicClient;
    private final ObjectMapper mapper;

    @Value("${app.anthropic.api-key:not-set}")
    private String apiKey;

    @Value("${app.sentiment.anthropic-model:claude-haiku-4-5-20251001}")
    private String model;

    public StrategyEnhancementService(WebClient.Builder builder, ObjectMapper mapper) {
        this.anthropicClient = builder.baseUrl("https://api.anthropic.com").build();
        this.mapper = mapper;
    }

    public record EnhancementSuggestion(
        String strategyName,
        String summary,               // 2-3 sentence diagnosis
        List<String> ruleChanges,     // concrete "change X to Y" suggestions
        List<String> newFilters,      // new conditions to add
        List<String> removeFilters,   // conditions to remove / relax
        String expectedImpact,        // "Win rate should increase from X% to ~Y%"
        String rawResponse            // full Claude response for display
    ) {}

    /**
     * Analyse a completed optimization result and return Claude's suggestions.
     * Returns a fallback suggestion if the API key is not set or call fails.
     */
    public EnhancementSuggestion analyse(OptimizationResult opt, BacktestResult bestResult) {
        if ("not-set".equals(apiKey)) {
            return fallback(opt.strategyName(), "ANTHROPIC_API_KEY not configured.");
        }
        try {
            String prompt = buildPrompt(opt, bestResult);
            String raw    = callClaude(prompt);
            return parse(opt.strategyName(), raw);
        } catch (Exception e) {
            log.error("Claude strategy enhancement failed for {}: {}", opt.strategyName(), e.getMessage());
            return fallback(opt.strategyName(), "API call failed: " + e.getMessage());
        }
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private String buildPrompt(OptimizationResult opt, BacktestResult best) {
        BacktestResult def = opt.defaultResult();

        // Count exit reasons from trade log
        long targets  = best.trades().stream().filter(t -> "TARGET".equals(t.exitReason())).count();
        long slHits   = best.trades().stream().filter(t -> "STOP_LOSS".equals(t.exitReason())).count();
        long trails   = best.trades().stream().filter(t -> "TRAIL_STOP".equals(t.exitReason())).count();
        long timeExit = best.trades().stream().filter(t -> "TIME_EXIT".equals(t.exitReason())).count();

        // Top 3 and bottom 3 trials from optimizer
        var trials = opt.allTrials();
        var top3   = trials.stream().limit(3).toList();
        var bot3   = trials.stream().skip(Math.max(0, trials.size() - 3)).toList();

        return """
You are a quantitative trading strategy analyst specialising in Nifty 50 intraday options.

I have backtested the strategy "%s" on Nifty 50 5-minute candles and run a parameter grid search.
Below is the full performance data. Provide CONCRETE, SPECIFIC improvement suggestions.

═══ CURRENT BEST PARAMETERS (after grid search) ═══
%s

═══ PERFORMANCE WITH BEST PARAMS ═══
• Trades: %d | Win Rate: %.1f%% | Profit Factor: %.2f
• Net P&L: ₹%.0f | Expectancy/trade: ₹%.0f | Max Drawdown: ₹%.0f
• Exit breakdown: TARGET=%d  STOP_LOSS=%d  TRAIL_STOP=%d  TIME_EXIT=%d
• Avg Win: ₹%.0f | Avg Loss: ₹%.0f | Costs paid: ₹%.0f

═══ BEFORE OPTIMIZATION (default params) ═══
• Trades: %d | Win Rate: %.1f%% | Net P&L: ₹%.0f | Expectancy: ₹%.0f

═══ OPTIMIZER IMPROVEMENT ═══
%s

═══ TOP 3 PARAMETER COMBOS (highest score) ═══
%s

═══ BOTTOM 3 PARAMETER COMBOS (worst score) ═══
%s

═══ STRATEGY TYPE ═══
This is an intraday strategy on Nifty 50. It trades options (CE = bullish, PE = bearish).
Lot size = 75 units. Typical ATR on 5m candle = 30-80 Nifty points.
Current win rate target: 60%%. Current target is to achieve positive expectancy > ₹500/trade.

Please respond in EXACTLY this format:

DIAGNOSIS: [2-3 sentences explaining the main problem — too many SL hits? TIME_EXIT losses? Entry too aggressive?]

RULE_CHANGES:
- [specific change 1, e.g. "Raise ADX minimum from 22 to 26 — removes low-momentum false signals"]
- [specific change 2]
- [specific change 3]

NEW_FILTERS:
- [new condition to add, e.g. "Only enter if current 5m candle has volume > 150%% of 20-bar average"]
- [new condition 2]

REMOVE_FILTERS:
- [condition to remove or relax, or "None" if no removals recommended]

EXPECTED_IMPACT: [e.g. "Win rate should increase from 45%% to ~58-62%%. TIME_EXIT trades should drop by ~30%%."]

Be specific with numbers. Do not give generic advice.
""".formatted(
            opt.strategyName(),
            formatParams(opt.bestParams()),
            best.totalTrades(), best.winRate() * 100, best.profitFactor(),
            best.totalNetPnl(), best.expectancyPerTrade(), best.maxDrawdown(),
            targets, slHits, trails, timeExit,
            best.avgWin(), best.avgLoss(), best.totalCosts(),
            def.totalTrades(), def.winRate() * 100, def.totalNetPnl(), def.expectancyPerTrade(),
            opt.improvement(),
            formatTrials(top3),
            formatTrials(bot3)
        );
    }

    private String formatParams(Map<String, Double> params) {
        var sb = new StringBuilder();
        params.forEach((k, v) -> sb.append(String.format("  %s = %.3f\n", k, v)));
        return sb.toString();
    }

    private String formatTrials(List<OptimizationResult.TrialResult> trials) {
        var sb = new StringBuilder();
        for (var t : trials) {
            sb.append(String.format("  params=%s score=%.2f winRate=%.1f%% pf=%.2f expectancy=₹%.0f\n",
                t.params(), t.score(), t.winRate() * 100, t.profitFactor(), t.expectancy()));
        }
        return sb.isEmpty() ? "  (none)" : sb.toString();
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private String callClaude(String prompt) {
        var body = Map.of(
            "model", model,
            "max_tokens", 1500,
            "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        String response = anthropicClient.post()
            .uri("/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .block();

        JsonNode root = null;
        try { root = mapper.readTree(response); }
        catch (Exception e) { return response; }
        return root.path("content").get(0).path("text").asText(response);
    }

    // ── Response parser ───────────────────────────────────────────────────────

    private EnhancementSuggestion parse(String strategyName, String raw) {
        String summary        = extract(raw, "DIAGNOSIS:", "RULE_CHANGES:");
        List<String> rules    = extractBullets(raw, "RULE_CHANGES:", "NEW_FILTERS:");
        List<String> newF     = extractBullets(raw, "NEW_FILTERS:", "REMOVE_FILTERS:");
        List<String> removeF  = extractBullets(raw, "REMOVE_FILTERS:", "EXPECTED_IMPACT:");
        String impact         = extract(raw, "EXPECTED_IMPACT:", null);

        return new EnhancementSuggestion(strategyName, summary.trim(),
            rules, newF, removeF, impact.trim(), raw);
    }

    private String extract(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start < 0) return "";
        start += startMarker.length();
        int end = endMarker != null ? text.indexOf(endMarker, start) : text.length();
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    private List<String> extractBullets(String text, String startMarker, String endMarker) {
        String section = extract(text, startMarker, endMarker);
        return section.lines()
            .map(String::trim)
            .filter(l -> l.startsWith("-"))
            .map(l -> l.substring(1).trim())
            .filter(l -> !l.isBlank())
            .toList();
    }

    private EnhancementSuggestion fallback(String name, String reason) {
        return new EnhancementSuggestion(name,
            "Claude analysis unavailable: " + reason,
            List.of("Run a backtest first, then set ANTHROPIC_API_KEY to enable AI analysis"),
            List.of(), List.of(), "", reason);
    }
}
