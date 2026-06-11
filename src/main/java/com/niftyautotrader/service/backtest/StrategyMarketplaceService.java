package com.niftyautotrader.service.backtest;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import com.niftyautotrader.service.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Computes multi-window performance cards for all registered strategies.
 * Used by the strategy marketplace page (/strategies).
 *
 * Performance windows: last 30 / 90 / 180 calendar days.
 * Returns (%) are computed against the strategy's estimated minimum capital.
 */
@Service
public class StrategyMarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(StrategyMarketplaceService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SYMBOL  = "NIFTY";
    private static final String TF_5M   = "5m";
    private static final int    WINDOWS = 4;

    /** Human-readable metadata per strategy. */
    private static final Map<String, String[]> META = Map.of(
        "EMA_CROSSOVER_9_21",       new String[]{"EMA Crossover 9/21",   "Trend Following",  "5m", "50000"},
        "OPENING_RANGE_BREAKOUT",   new String[]{"Opening Range Breakout","Breakout",         "5m", "50000"},
        "SUPERTREND_FOLLOWER",      new String[]{"Supertrend Follower",    "Trend Following",  "15m","75000"},
        "VWAP_BOLLINGER_REVERSION", new String[]{"VWAP Bollinger Reversion","Mean Reversion", "5m", "50000"},
        "MACD_CROSSOVER",           new String[]{"MACD Crossover",         "Trend Following",  "5m", "50000"},
        "EMA21_PULLBACK",           new String[]{"EMA21 Pullback",          "Trend Following",  "5m", "60000"},
        "BOLLINGER_SQUEEZE_BREAKOUT",new String[]{"Bollinger Squeeze",      "Breakout",         "5m", "75000"}
    );

    private final BacktestEngine engine;
    private final CandleRepository candleRepo;
    private final List<TradingStrategy> strategies;

    public StrategyMarketplaceService(BacktestEngine engine,
                                       CandleRepository candleRepo,
                                       List<TradingStrategy> strategies) {
        this.engine = engine;
        this.candleRepo = candleRepo;
        this.strategies = strategies;
    }

    public List<StrategyCard> buildCards() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        List<Candle> all180 = candleRepo.findBySymbolAndTimeframeAndOpenTimeAfterOrderByOpenTimeAsc(
            SYMBOL, TF_5M, now.minusDays(185));

        ZonedDateTime cutoff30  = now.minusDays(30);
        ZonedDateTime cutoff90  = now.minusDays(90);

        return strategies.stream()
            .map(s -> buildCard(s, all180, cutoff30, cutoff90))
            .toList();
    }

    private StrategyCard buildCard(TradingStrategy strategy,
                                    List<Candle> all180,
                                    ZonedDateTime cutoff30,
                                    ZonedDateTime cutoff90) {
        String name = strategy.getName();
        String[] meta = META.getOrDefault(name,
            new String[]{name, "Quantitative", "5m", "50000"});

        int capital = Integer.parseInt(meta[3]);

        List<Candle> candles30  = filter(all180, cutoff30);
        List<Candle> candles90  = filter(all180, cutoff90);

        BacktestResult r30  = safeRun(strategy, candles30,  WINDOWS);
        BacktestResult r90  = safeRun(strategy, candles90,  WINDOWS);
        BacktestResult r180 = safeRun(strategy, all180,     WINDOWS);

        BigDecimal capitalBd = BigDecimal.valueOf(capital);

        BigDecimal ret30  = pct(r30.totalNetPnl(),  capitalBd);
        BigDecimal ret90  = pct(r90.totalNetPnl(),  capitalBd);
        BigDecimal ret180 = pct(r180.totalNetPnl(), capitalBd);

        log.debug("[Marketplace] {} | 30d={}% 90d={}% 180d={}%", name, ret30, ret90, ret180);

        return new StrategyCard(
            name,
            meta[0],
            meta[1],
            "Intraday",
            meta[2],
            capital,
            ret30, ret90, ret180,
            r30.totalTrades(), r90.totalTrades(), r180.totalTrades(),
            r30.winRate(),
            r30.profitFactor(),
            r30.maxDrawdown(),
            r30.expectancyPerTrade(),
            r30.promotable(),
            r30.verdict(),
            r30.equityCurve()
        );
    }

    private BacktestResult safeRun(TradingStrategy s, List<Candle> candles, int windows) {
        try {
            return engine.runWalkForward(s, candles, windows);
        } catch (Exception e) {
            log.warn("[Marketplace] backtest failed for {}: {}", s.getName(), e.getMessage());
            return emptyResult(s.getName());
        }
    }

    private static List<Candle> filter(List<Candle> candles, ZonedDateTime after) {
        return candles.stream()
            .filter(c -> c.getOpenTime() != null && c.getOpenTime().isAfter(after))
            .toList();
    }

    private static BigDecimal pct(BigDecimal pnl, BigDecimal capital) {
        if (capital.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return pnl.multiply(BigDecimal.valueOf(100))
            .divide(capital, 2, RoundingMode.HALF_UP);
    }

    private static BacktestResult emptyResult(String name) {
        return new BacktestResult(name, 0, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
            List.of(BigDecimal.ZERO), false, "NOT PROMOTABLE — no data");
    }
}
