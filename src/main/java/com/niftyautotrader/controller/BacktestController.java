package com.niftyautotrader.controller;

import com.niftyautotrader.repository.CandleRepository;
import com.niftyautotrader.service.backtest.BacktestEngine;
import com.niftyautotrader.service.backtest.BacktestResult;
import com.niftyautotrader.service.strategy.TradingStrategy;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/backtest")
public class BacktestController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BacktestEngine backtestEngine;
    private final CandleRepository candleRepo;
    private final Map<String, TradingStrategy> strategiesByName;

    public BacktestController(BacktestEngine backtestEngine,
                               CandleRepository candleRepo,
                               List<TradingStrategy> strategies) {
        this.backtestEngine = backtestEngine;
        this.candleRepo = candleRepo;
        this.strategiesByName = strategies.stream()
            .collect(Collectors.toMap(TradingStrategy::getName, s -> s));
    }

    @GetMapping
    public String backtestForm(Model model) {
        model.addAttribute("strategies", strategiesByName.keySet());
        model.addAttribute("symbol", "NIFTY");
        return "backtest";
    }

    @PostMapping("/run")
    public String runBacktest(@RequestParam String strategyName,
                               @RequestParam(defaultValue = "NIFTY") String symbol,
                               @RequestParam(defaultValue = "4") int windows,
                               Model model) {
        TradingStrategy strategy = strategiesByName.get(strategyName);
        if (strategy == null) {
            model.addAttribute("error", "Unknown strategy: " + strategyName);
            model.addAttribute("strategies", strategiesByName.keySet());
            return "backtest";
        }

        ZonedDateTime cutoff = ZonedDateTime.now(IST).minusMonths(6);
        var candles = candleRepo.findBySymbolAndTimeframeAndOpenTimeAfterOrderByOpenTimeAsc(
            symbol, "5m", cutoff);

        BacktestResult result = backtestEngine.runWalkForward(strategy, candles, windows);

        model.addAttribute("result", result);
        model.addAttribute("strategies", strategiesByName.keySet());
        model.addAttribute("selectedStrategy", strategyName);
        model.addAttribute("symbol", symbol);
        return "backtest";
    }
}
