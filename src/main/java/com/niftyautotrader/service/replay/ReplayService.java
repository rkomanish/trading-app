package com.niftyautotrader.service.replay;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates market-replay sessions: loads historical Nifty candles for a chosen
 * day, aggregates them to any supported timeframe, and holds the per-browser demo
 * trading book.
 *
 * Self-contained demo module — never routes through RiskEngine / PaperBroker.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SYMBOL = "NIFTY";
    public static final List<String> TIMEFRAMES = List.of("1m", "3m", "5m", "15m", "30m", "60m");

    private final CandleRepository candleRepo;
    private final Map<String, ReplaySession> sessions = new ConcurrentHashMap<>();

    public ReplayService(CandleRepository candleRepo) {
        this.candleRepo = candleRepo;
    }

    /** A single OHLCV bar for the chart (time = IST wall-clock seconds for display). */
    public record Bar(long time, double open, double high, double low, double close, long volume, String label) {}

    /** One side (CE or PE) of an option chain row. */
    public record OptionQuote(double ltp, double delta, double theta, double iv) {}

    /** One strike row of the synthetic option chain. */
    public record ChainRow(double strike, boolean atm, OptionQuote ce, OptionQuote pe) {}

    /** Nearest weekly expiry (Thursday) on/after the session date; time-to-expiry in years. */
    public double tteYearsFor(LocalDate sessionDate) {
        LocalDate d = sessionDate;
        while (d.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) d = d.plusDays(1);
        long days = java.time.temporal.ChronoUnit.DAYS.between(sessionDate, d);
        // Same-day (0DTE) keeps a small fraction so premiums aren't pure intrinsic.
        double effDays = days == 0 ? 0.4 : days;
        return effDays / 365.0;
    }

    /**
     * Build a synthetic Black-Scholes option chain around the spot.
     * @param spot       current Nifty spot
     * @param iv         implied volatility (fraction, e.g. 0.12)
     * @param tteYears   time to expiry in years
     * @param step       strike interval (e.g. 50)
     * @param numStrikes strikes on EACH side of ATM
     */
    public List<ChainRow> buildChain(double spot, double iv, double tteYears, int step, int numStrikes) {
        long atm = Math.round(spot / step) * (long) step;
        List<ChainRow> rows = new ArrayList<>();
        for (int i = numStrikes; i >= -numStrikes; i--) {
            double strike = atm + (long) i * step;
            if (strike <= 0) continue;
            var ce = BlackScholes.price(spot, strike, tteYears, iv, true);
            var pe = BlackScholes.price(spot, strike, tteYears, iv, false);
            rows.add(new ChainRow(strike, Math.round(strike) == atm,
                new OptionQuote(round2(ce.price()), round3(ce.delta()), round2(ce.theta()), iv),
                new OptionQuote(round2(pe.price()), round3(pe.delta()), round2(pe.theta()), iv)));
        }
        return rows;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    /** Base timeframe used to build everything else. Prefer 1m; fall back to 5m if no 1m data. */
    private String baseTimeframe() {
        if (candleRepo.existsBySymbolAndTimeframe(SYMBOL, "1m")) return "1m";
        if (candleRepo.existsBySymbolAndTimeframe(SYMBOL, "5m")) return "5m";
        if (candleRepo.existsBySymbolAndTimeframe(SYMBOL, "15m")) return "15m";
        return "1m";
    }

    /** Distinct trading days (descending) that have data available for replay. */
    public List<LocalDate> availableDates() {
        String base = baseTimeframe();
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (Candle c : candleRepo.findBySymbolAndTimeframeOrderByOpenTimeAsc(SYMBOL, base)) {
            dates.add(c.getOpenTime().withZoneSameInstant(IST).toLocalDate());
        }
        List<LocalDate> list = new ArrayList<>(dates);
        java.util.Collections.reverse(list);
        return list;
    }

    /** Load and aggregate one trading day's candles for the requested timeframe. */
    public List<Bar> dayCandles(LocalDate date, String timeframe) {
        if (!TIMEFRAMES.contains(timeframe)) timeframe = "5m";
        String base = baseTimeframe();

        ZonedDateTime from = date.atStartOfDay(IST);
        ZonedDateTime to   = date.plusDays(1).atStartOfDay(IST);
        List<Candle> baseCandles = candleRepo
            .findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(SYMBOL, base, from, to);

        List<Candle> tfCandles;
        if (base.equals("1m")) {
            tfCandles = CandleAggregator.aggregate(baseCandles, timeframe);
        } else if (base.equals(timeframe)) {
            tfCandles = baseCandles;
        } else if (CandleAggregator.minutesOf(timeframe) >= CandleAggregator.minutesOf(base)
                   && CandleAggregator.minutesOf(timeframe) % CandleAggregator.minutesOf(base) == 0) {
            // Aggregate from a coarser base (e.g. 5m -> 15m) when 1m isn't available.
            tfCandles = CandleAggregator.aggregate(baseCandles, timeframe);
        } else {
            // Cannot build a finer timeframe than the base — return the base candles as-is.
            log.debug("Cannot build {} from base {}; serving base candles", timeframe, base);
            tfCandles = baseCandles;
        }

        List<Bar> bars = new ArrayList<>(tfCandles.size());
        for (Candle c : tfCandles) {
            ZonedDateTime ist = c.getOpenTime().withZoneSameInstant(IST);
            // lightweight-charts renders timestamps in UTC; offset by IST so labels read as IST.
            long displayTime = c.getOpenTime().toEpochSecond() + 19800L;
            bars.add(new Bar(displayTime,
                c.getOpen().doubleValue(), c.getHigh().doubleValue(),
                c.getLow().doubleValue(), c.getClose().doubleValue(),
                c.getVolume(), ist.format(HM)));
        }
        return bars;
    }

    // ── Session management ──────────────────────────────────────────────────────

    public ReplaySession startSession(String sessionId, LocalDate date, String timeframe,
                                      BigDecimal capital, double iv) {
        ReplaySession s = new ReplaySession(date, timeframe, capital);
        s.setIv(iv);
        s.setTteYears(tteYearsFor(date));
        sessions.put(sessionId, s);
        log.info("Replay session started: id={} date={} tf={} capital={}", sessionId, date, timeframe, capital);
        return s;
    }

    public ReplaySession session(String sessionId) {
        return sessions.get(sessionId);
    }

    public void reset(String sessionId) {
        sessions.remove(sessionId);
    }
}
