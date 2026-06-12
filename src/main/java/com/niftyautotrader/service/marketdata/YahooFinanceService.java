package com.niftyautotrader.service.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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
 * Fetches historical candles from Yahoo Finance's public chart API.
 * Used to seed backtest data — NOT for live trading decisions.
 *
 * Yahoo symbol for Nifty 50 index: ^NSEI
 * Intraday limits: 5m data is available for roughly the last 60 days.
 */
@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WebClient yahooClient;
    private final ObjectMapper objectMapper;
    private final CandleRepository candleRepo;

    public YahooFinanceService(WebClient.Builder webClientBuilder,
                                ObjectMapper objectMapper,
                                CandleRepository candleRepo) {
        // Yahoo rejects requests without a browser-like User-Agent
        this.yahooClient = webClientBuilder
            .baseUrl("https://query1.finance.yahoo.com")
            .defaultHeader("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
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
     * @param yahooSymbol e.g. "^NSEI" for Nifty 50
     * @param appSymbol   symbol stored in our DB, e.g. "NIFTY"
     * @param interval    "5m", "15m", "1m"
     * @param range       "1mo", "5d", "60d" (Yahoo range strings)
     */
    public FetchResult fetchAndStore(String yahooSymbol, String appSymbol,
                                      String interval, String range) {
        List<Candle> candles = null;
        Exception lastError = null;
        // Retry up to 3 times with 2s backoff; on reset errors also swap to query2
        String[] hosts = {"https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com"};
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String host = hosts[attempt % hosts.length];
                candles = fetchFromHost(host, yahooSymbol, appSymbol, interval, range);
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("Yahoo fetch attempt {} failed: {} — retrying", attempt + 1, e.getMessage());
                if (attempt < 2) {
                    try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        if (candles == null) {
            log.error("Yahoo fetch failed after 3 attempts: {}", lastError != null ? lastError.getMessage() : "unknown");
            return new FetchResult(0, 0, 0, "Yahoo fetch failed: " + (lastError != null ? lastError.getMessage() : "unknown"));
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

    /** Fetch candles and render them as CSV in our standard import format. */
    public String fetchAsCsv(String yahooSymbol, String appSymbol,
                              String interval, String range) throws Exception {
        List<Candle> candles = fetchFromHost("https://query1.finance.yahoo.com", yahooSymbol, appSymbol, interval, range);
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

    private List<Candle> fetchFromHost(String host, String yahooSymbol, String appSymbol,
                                        String interval, String range) throws Exception {
        WebClient client = yahooClient.mutate().baseUrl(host).build();
        String body = client.get()
            .uri(uri -> uri.path("/v8/finance/chart/{symbol}")
                .queryParam("interval", interval)
                .queryParam("range", range)
                .build(yahooSymbol))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(10))
            .block();

        JsonNode root = objectMapper.readTree(body);
        JsonNode result = root.path("chart").path("result").get(0);
        if (result == null || result.isMissingNode()) {
            String err = root.path("chart").path("error").path("description").asText("unknown error");
            throw new IllegalStateException("Yahoo returned no data: " + err);
        }

        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").get(0);
        JsonNode opens = quote.path("open");
        JsonNode highs = quote.path("high");
        JsonNode lows = quote.path("low");
        JsonNode closes = quote.path("close");
        JsonNode volumes = quote.path("volume");

        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            // Yahoo returns nulls for gaps — skip incomplete bars
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
        return candles;
    }

    private BigDecimal scaled(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
