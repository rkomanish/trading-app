package com.niftyautotrader.service.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niftyautotrader.config.DhanProperties;
import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches intraday candles from Dhan's FREE market-data API.
 *
 * Endpoint: POST {baseUrl}/v2/charts/intraday
 * Auth:     headers access-token + client-id
 *
 * Request body:
 *   {
 *     "securityId":"13", "exchangeSegment":"IDX_I", "instrument":"INDEX",
 *     "interval":"5", "fromDate":"2026-06-01", "toDate":"2026-06-11"
 *   }
 *
 * Response (parallel arrays):
 *   { "open":[...], "high":[...], "low":[...], "close":[...],
 *     "volume":[...], "timestamp":[<epoch seconds>...] }
 *
 * Dhan interval values are minutes as strings: "1", "5", "15", "25", "60".
 * Our timeframe strings are "1m", "5m", "15m" — we translate between them.
 */
@Service
public class DhanMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(DhanMarketDataService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WebClient dhanClient;
    private final ObjectMapper objectMapper;
    private final CandleRepository candleRepo;
    private final DhanProperties dhan;

    public DhanMarketDataService(WebClient.Builder webClientBuilder,
                                  ObjectMapper objectMapper,
                                  CandleRepository candleRepo,
                                  DhanProperties dhan) {
        this.dhanClient = webClientBuilder
            .baseUrl(dhan.getBaseUrl())
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
        this.objectMapper = objectMapper;
        this.candleRepo = candleRepo;
        this.dhan = dhan;
    }

    public record FetchResult(int fetched, int saved, int duplicates, String error) {
        public boolean isSuccess() { return error == null; }
    }

    public boolean isConfigured() { return dhan.isConfigured(); }

    /**
     * Fetch candles for [fromDate, toDate] and persist new ones.
     *
     * @param appSymbol stored symbol, e.g. "NIFTY"
     * @param timeframe our timeframe string: "1m", "5m", "15m"
     */
    public FetchResult fetchAndStore(String appSymbol, String timeframe,
                                      LocalDate fromDate, LocalDate toDate) {
        if (!dhan.isConfigured()) {
            return new FetchResult(0, 0, 0, "Dhan API not configured (set DHAN_CLIENT_ID + DHAN_ACCESS_TOKEN)");
        }

        List<Candle> candles;
        try {
            candles = fetch(appSymbol, timeframe, fromDate, toDate);
        } catch (Exception e) {
            log.error("Dhan fetch failed: {}", e.getMessage());
            return new FetchResult(0, 0, 0, "Dhan fetch failed: " + e.getMessage());
        }

        int saved = 0, duplicates = 0;
        for (Candle c : candles) {
            // Existence check avoids constraint-violation transaction poisoning
            if (candleRepo.existsBySymbolAndTimeframeAndOpenTime(
                    c.getSymbol(), c.getTimeframe(), c.getOpenTime())) {
                duplicates++;
            } else {
                candleRepo.save(c);
                saved++;
            }
        }
        log.info("Dhan import: {} fetched, {} saved, {} duplicates for {} [{}] {}..{}",
            candles.size(), saved, duplicates, appSymbol, timeframe, fromDate, toDate);
        return new FetchResult(candles.size(), saved, duplicates, null);
    }

    private List<Candle> fetch(String appSymbol, String timeframe,
                                LocalDate fromDate, LocalDate toDate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("securityId", dhan.getNiftySecurityId());
        body.put("exchangeSegment", dhan.getNiftyExchangeSegment());
        body.put("instrument", dhan.getNiftyInstrument());
        body.put("interval", toDhanInterval(timeframe));
        body.put("fromDate", fromDate.format(DATE));
        body.put("toDate", toDate.format(DATE));

        String response = dhanClient.post()
            .uri("/v2/charts/intraday")
            .header("access-token", dhan.getAccessToken())
            .header("client-id", dhan.getClientId())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .block();

        JsonNode root = objectMapper.readTree(response);

        // Dhan returns errorType/errorMessage on failure
        if (root.has("errorType") || root.has("errorCode")) {
            String msg = root.path("errorMessage").asText(root.path("errorType").asText("unknown"));
            throw new IllegalStateException("Dhan API error: " + msg);
        }

        JsonNode opens = root.path("open");
        JsonNode highs = root.path("high");
        JsonNode lows = root.path("low");
        JsonNode closes = root.path("close");
        JsonNode volumes = root.path("volume");
        JsonNode timestamps = root.path("timestamp");

        if (opens.isMissingNode() || timestamps.isMissingNode()) {
            throw new IllegalStateException("Dhan response missing OHLC arrays");
        }

        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            Candle c = new Candle();
            c.setSymbol(appSymbol);
            c.setTimeframe(timeframe);
            c.setOpenTime(ZonedDateTime.ofInstant(
                Instant.ofEpochSecond(timestamps.get(i).asLong()), IST));
            c.setOpen(scaled(opens.get(i).asDouble()));
            c.setHigh(scaled(highs.get(i).asDouble()));
            c.setLow(scaled(lows.get(i).asDouble()));
            c.setClose(scaled(closes.get(i).asDouble()));
            c.setVolume(volumes.isMissingNode() || volumes.get(i).isNull()
                ? 0L : volumes.get(i).asLong());
            candles.add(c);
        }
        return candles;
    }

    /** Translate our timeframe ("5m") to Dhan's minute string ("5"). */
    private String toDhanInterval(String timeframe) {
        return switch (timeframe) {
            case "1m" -> "1";
            case "5m" -> "5";
            case "15m" -> "15";
            case "25m" -> "25";
            case "60m", "1h" -> "60";
            default -> throw new IllegalArgumentException("Unsupported timeframe for Dhan: " + timeframe);
        };
    }

    private BigDecimal scaled(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
