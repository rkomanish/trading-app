package com.niftyautotrader.service.backtest;

import com.niftyautotrader.model.BacktestRun;
import com.niftyautotrader.repository.BacktestRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Map;

/** Converts a BacktestResult into a BacktestRun entity and persists it. */
@Service
public class BacktestPersistenceService {

    private final BacktestRunRepository repo;
    private final ObjectMapper mapper;

    public BacktestPersistenceService(BacktestRunRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public BacktestRun save(BacktestResult result, String symbol,
                             java.time.LocalDate from, java.time.LocalDate to,
                             Map<String, Double> params,
                             boolean isOptimized, Double optimizerScore) {
        BacktestRun run = new BacktestRun();
        run.setStrategyName(result.strategyName());
        run.setSymbol(symbol != null ? symbol : "NIFTY");
        run.setFromDate(from);
        run.setToDate(to);
        run.setTradingDays(result.tradingDaysWithData());
        run.setTotalTrades(result.totalTrades());
        run.setWinningTrades(result.winningTrades());
        run.setLosingTrades(result.losingTrades());
        run.setWinRate(result.winRate());
        run.setTotalGrossPnl(result.totalGrossPnl());
        run.setTotalCosts(result.totalCosts());
        run.setTotalNetPnl(result.totalNetPnl());
        run.setAvgWin(result.avgWin());
        run.setAvgLoss(result.avgLoss());
        run.setExpectancyPerTrade(result.expectancyPerTrade());
        run.setMaxDrawdown(result.maxDrawdown());
        run.setProfitFactor(result.profitFactor());
        run.setPromotable(result.promotable());
        run.setVerdict(result.verdict());
        run.setOptimized(isOptimized);
        run.setOptimizerScore(optimizerScore);
        run.setRunAt(ZonedDateTime.now());

        if (params != null && !params.isEmpty()) {
            try { run.setParamsJson(mapper.writeValueAsString(params)); }
            catch (Exception ignored) {}
        }
        return repo.save(run);
    }
}
