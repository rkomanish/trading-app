package com.niftyautotrader.service.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches historical OHLCV candles from Yahoo Finance's chart API (v8).
 * Used to seed backtest data — NOT for live trading decisions.
 *
 * Authentication strategy (crumb-free):
 *   Yahoo Finance's v8 chart endpoint accepts period1/period2 Unix timestamps and
 *   does NOT require a crumb when the right browser headers (Referer, Accept) are set.
 *   The crumb is only needed for the range-based endpoint, which Yahoo now protects.
 *
 * Yahoo intraday data limits (hard limits from their API):
 *   1m  → max last 7 days
 *   5m  → max last 60 days
 *   15m → max last 60 days
 */
@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Yahoo API interval limits in days
    private static final int MAX_DAYS_1M  = 7;
    private static final int MAX_DAYS_5M  = 60;
    private static final int MAX_DAYS_15M = 60;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CandleRepository candleRepo;

    public YahooFinanceService(WebClient.Builder webClientBuilder,
                                ObjectMapper objectMapper,
                                CandleRepository candleRepo) {
        HttpClient httpClient = HttpClient.create()
            .followRedirect(true)
            .httpResponseDecoder(spec -> spec.maxHeaderSize(65536));

        this.webClient = webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .defaultHeader("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .defaultHeader("Accept", "application/json, text/plain, */*")
            .defaultHeader("Accept-Language", "en-US,en;q=0.9")
            .defaultHeader("Referer", "https://finance.yahoo.com/")
            .defaultHeader("Origin", "https://finance.yahoo.com")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
        this.objectMapper = objectMapper;
        this.candleRepo = candleRepo;
    }

    public record FetchResult(int fetched, int saved, int duplicates, String error) {
        public boolean isSuccess() { return error == null; }
    }

    /**
     * Fetch candles from Yahoo and save them to the candle table.
     *
     * @param yahooSymbol  e.g. "^NSEI" for Nifty 50
     * @param appSymbol    symbol stored in our DB, e.g. "NIFTY"
     * @param interval     "1m", "5m", "15m"
     * @param range        "7d", "1mo", "3mo", "60d" — converted to period1/period2 timestamps
     */
    public FetchResult fetchAndStore(String yahooSymbol, String appSymbol,
                                      String interval, String range) {
        List<Candle> candles = null;
        Exception lastError = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                candles = fetch(yahooSymbol, appSymbol, interval, range);
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("Yahoo fetch attempt {} failed: {} — retrying", attempt + 1, e.getMessage());
                if (attempt < 2) {
                    try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (candles == null) {
            String msg = lastError != null ? lastError.getMessage() : "unknown";
            log.error("Yahoo fetch failed after 3 attempts: {}", msg);
            return new FetchResult(0, 0, 0, "Yahoo fetch failed: " + msg);
        }

        int saved = 0, duplicates = 0;
        for (Candle c : candles) {
            if (candleRepo.existsBySymbolAndTimeframeAndOpenTime(
                    c.getSymbol(), c.getTimeframe(), c.getOpenTime())) {
                duplicates++;
            } else {
                candleRepo.save(c);
                saved++;
            }
        }
        log.info("Yahoo import: {} fetched, {} saved, {} duplicates for {} [{}]",
            candles.size(), saved, duplicates, appSymbol, interval);
        return new FetchResult(candles.size(), saved, duplicates, null);
    }

    /** Fetch candles and render them as CSV. */
    public String fetchAsCsv(String yahooSymbol, String appSymbol,
                              String interval, String range) throws Exception {
        List<Candle> candles = fetch(yahooSymbol, appSymbol, interval, range);
        StringBuilder sb = new StringBuilder("timestamp,open,high,low,close,volume\n");
        for (Candle c : candles) {
            sb.append(c.getOpenTime().format(CSV_TS)).append(',')
              .append(c.getOpen()).append(',')
              .append(c.getHigh()).append(',')
              .append(c.getLow()).append(',')
              .append(c.getClose()).append(',')
              .append(c.getVolume()).append('\n');
        }
        return sb.toString();
    }

    private List<Candle> fetch(String yahooSymbol, String appSymbol,
                                String interval, String range) throws Exception {
        long period2 = Instant.now().getEpochSecond();
        long period1 = period2 - rangeToDays(interval, range) * 86400L;

        log.info("Fetching Yahoo {} {} (period1={}, period2={}) symbol={}",
            interval, range, period1, period2, yahooSymbol);

        // Try query1 first, fall back to query2 on failure
        String body = null;
        Exception firstEx = null;
        for (String host : new String[]{"query1.finance.yahoo.com", "query2.finance.yahoo.com"}) {
            try {
                final long p1 = period1, p2 = period2;
                body = webClient.get()
                    .uri(uri -> uri.scheme("https").host(host)
                        .path("/v8/finance/chart/{symbol}")
                        .queryParam("period1", p1)
                        .queryParam("period2", p2)
                        .queryParam("interval", interval)
                        .queryParam("includePrePost", "false")
                        .queryParam("events", "div,splits")
                        .build(yahooSymbol))
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        res -> res.bodyToMono(String.class).map(err ->
                            new RuntimeException("Yahoo HTTP " + res.statusCode().value()
                                + " from " + host + ": " + err.substring(0, Math.min(200, err.length())))))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();
                break;
            } catch (Exception e) {
                log.debug("Host {} failed: {}", host, e.getMessage());
                if (firstEx == null) firstEx = e;
            }
        }
        if (body == null) throw firstEx != null ? firstEx : new IllegalStateException("No response from Yahoo");

        return parseResponse(body, appSymbol, interval);
    }

    private List<Candle> parseResponse(String body, String appSymbol, String interval) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode result = root.path("chart").path("result").get(0);
        if (result == null || result.isMissingNode()) {
            String err = root.path("chart").path("error").path("description").asText("unknown error");
            throw new IllegalStateException("Yahoo returned no data: " + err);
        }

        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").get(0);
        JsonNode opens   = quote.path("open");
        JsonNode highs   = quote.path("high");
        JsonNode lows    = quote.path("low");
        JsonNode closes  = quote.path("close");
        JsonNode volumes = quote.path("volume");

        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            if (opens.get(i).isNull() || highs.get(i).isNull()
                || lows.get(i).isNull() || closes.get(i).isNull()) {
                continue;
            }
            Candle c = new Candle();
            c.setSymbol(appSymbol);
            c.setTimeframe(interval);
            c.setOpenTime(ZonedDateTime.ofInstant(
                Instant.ofEpochSecond(timestamps.get(i).asLong()), IST));
            c.setOpen(scaled(opens.get(i).asDouble()));
            c.setHigh(scaled(highs.get(i).asDouble()));
            c.setLow(scaled(lows.get(i).asDouble()));
            c.setClose(scaled(closes.get(i).asDouble()));
            c.setVolume(volumes.get(i).isNull() ? 0L : volumes.get(i).asLong());
            candles.add(c);
        }
        log.info("Parsed {} candles from Yahoo response for {} [{}]", candles.size(), appSymbol, interval);
        return candles;
    }

    /** Convert a range string like "60d", "1mo", "3mo", "7d" to days, capped by interval limits. */
    private int rangeToDays(String interval, String range) {
        int requested;
        if (range.endsWith("d")) {
            requested = Integer.parseInt(range.replace("d", ""));
        } else if (range.endsWith("mo")) {
            requested = Integer.parseInt(range.replace("mo", "")) * 30;
        } else if (range.endsWith("y")) {
            requested = Integer.parseInt(range.replace("y", "")) * 365;
        } else {
            requested = 30; // default
        }

        int maxDays = switch (interval) {
            case "1m"  -> MAX_DAYS_1M;
            case "5m"  -> MAX_DAYS_5M;
            case "15m" -> MAX_DAYS_15M;
            default    -> 365;
        };

        int days = Math.min(requested, maxDays);
        if (days < requested) {
            log.info("Capping {} range from {}d to {}d (Yahoo {} limit)", interval, requested, days, interval);
        }
        return days;
    }

    private BigDecimal scaled(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
