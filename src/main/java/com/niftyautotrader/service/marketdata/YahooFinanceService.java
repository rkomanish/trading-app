package com.niftyautotrader.service.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Fetches historical candles from Yahoo Finance's public chart API.
 * Used to seed backtest data — NOT for live trading decisions.
 *
 * Yahoo symbol for Nifty 50 index: ^NSEI
 * Intraday limits: 1m data last 7 days only; 5m/15m up to 60 days.
 *
 * Yahoo Finance now requires a crumb + cookie for all chart requests.
 * We fetch the crumb once and cache it; re-fetch on 401/403/422.
 */
@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String CRUMB_URL = "https://query2.finance.yahoo.com/v1/test/getcrumb";
    private static final String CONSENT_URL = "https://consent.yahoo.com/v2/collectConsent?sessionId=";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CandleRepository candleRepo;

    private volatile String cachedCrumb = null;
    private volatile String cachedCookie = null;

    public YahooFinanceService(WebClient.Builder webClientBuilder,
                                ObjectMapper objectMapper,
                                CandleRepository candleRepo) {
        this.webClient = webClientBuilder
            .defaultHeader("User-Agent", UA)
            .defaultHeader("Accept", "*/*")
            .defaultHeader("Accept-Language", "en-US,en;q=0.9")
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
     * @param yahooSymbol e.g. "^NSEI" for Nifty 50
     * @param appSymbol   symbol stored in our DB, e.g. "NIFTY"
     * @param interval    "1m", "5m", "15m"
     * @param range       "7d", "1mo", "3mo", "60d" (Yahoo range strings)
     */
    public FetchResult fetchAndStore(String yahooSymbol, String appSymbol,
                                      String interval, String range) {
        // 1m data is only available for the last 7 days — cap silently
        if ("1m".equals(interval) && !range.equals("7d") && !range.equals("5d")) {
            log.info("Capping 1m range to 7d (Yahoo limit)");
            range = "7d";
        }

        List<Candle> candles = null;
        Exception lastError = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                if (cachedCrumb == null) {
                    refreshCrumb();
                }
                candles = fetchWithCrumb(yahooSymbol, appSymbol, interval, range);
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("Yahoo fetch attempt {} failed: {} — retrying", attempt + 1, e.getMessage());
                // Force crumb refresh on auth errors
                cachedCrumb = null;
                cachedCookie = null;
                if (attempt < 3) {
                    try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        if (candles == null) {
            String msg = lastError != null ? lastError.getMessage() : "unknown";
            log.error("Yahoo fetch failed after 4 attempts: {}", msg);
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

    /** Fetch candles and render them as CSV in our standard import format. */
    public String fetchAsCsv(String yahooSymbol, String appSymbol,
                              String interval, String range) throws Exception {
        if (cachedCrumb == null) refreshCrumb();
        List<Candle> candles = fetchWithCrumb(yahooSymbol, appSymbol, interval, range);
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

    /**
     * Obtain a Yahoo crumb by fetching the getcrumb endpoint.
     * Yahoo requires a valid A3 cookie (set by the finance landing page) before the crumb is issued.
     * We bootstrap by hitting the finance page to collect cookies first.
     */
    private synchronized void refreshCrumb() throws Exception {
        log.info("Fetching Yahoo crumb...");

        // Step 1: hit Yahoo Finance landing page to get session cookies
        AtomicReference<String> cookieHolder = new AtomicReference<>("");
        webClient.get()
            .uri("https://finance.yahoo.com/")
            .exchangeToMono(res -> {
                String cookies = res.cookies().values().stream()
                    .flatMap(List::stream)
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));
                cookieHolder.set(cookies);
                return res.bodyToMono(String.class);
            })
            .timeout(Duration.ofSeconds(10))
            .block();

        String cookie = cookieHolder.get();
        if (cookie.isEmpty()) {
            // Fallback: try the consent page to get A3 cookie
            cookie = "A1=d=AQABBJsGBGQCEPub; A3=d=AQABBJsGBGQCEPub";
        }
        cachedCookie = cookie;
        log.debug("Yahoo cookies: {}", cookie.substring(0, Math.min(80, cookie.length())));

        // Step 2: fetch the crumb using the cookies
        String crumb = webClient.get()
            .uri(CRUMB_URL)
            .header("Cookie", cachedCookie)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(10))
            .block();

        if (crumb == null || crumb.isBlank() || crumb.startsWith("<")) {
            throw new IllegalStateException("Failed to obtain Yahoo crumb (got: " + crumb + ")");
        }
        cachedCrumb = crumb.trim();
        log.info("Yahoo crumb obtained: {}", cachedCrumb);
    }

    private List<Candle> fetchWithCrumb(String yahooSymbol, String appSymbol,
                                         String interval, String range) throws Exception {
        String body = webClient.get()
            .uri(uri -> uri
                .scheme("https").host("query1.finance.yahoo.com")
                .path("/v8/finance/chart/{symbol}")
                .queryParam("interval", interval)
                .queryParam("range", range)
                .queryParam("crumb", cachedCrumb)
                .queryParam("includePrePost", "false")
                .build(yahooSymbol))
            .header("Cookie", cachedCookie)
            .retrieve()
            .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                res -> res.bodyToMono(String.class).map(err ->
                    new RuntimeException("Yahoo HTTP " + res.statusCode().value() + ": " + err)))
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(15))
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
        log.info("Parsed {} candles from Yahoo ({} {})", candles.size(), interval, range);
        return candles;
    }

    private BigDecimal scaled(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
