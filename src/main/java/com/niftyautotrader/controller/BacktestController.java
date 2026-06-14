package com.niftyautotrader.controller;

import com.niftyautotrader.model.BacktestRun;
import com.niftyautotrader.repository.BacktestRunRepository;
import com.niftyautotrader.repository.CandleRepository;
import com.niftyautotrader.service.backtest.*;
import com.niftyautotrader.service.marketdata.YahooFinanceService;
import com.niftyautotrader.service.strategy.TradingStrategy;
import com.niftyautotrader.service.strategy.TunableStrategy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
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
    private final CandleCsvImporter csvImporter;
    private final YahooFinanceService yahooService;
    private final BacktestPersistenceService persistence;
    private final StrategyOptimizer optimizer;
    private final StrategyEnhancementService enhancementService;
    private final BacktestRunRepository runRepo;
    private final Map<String, TradingStrategy> strategiesByName;

    public BacktestController(BacktestEngine backtestEngine,
                               CandleRepository candleRepo,
                               CandleCsvImporter csvImporter,
                               YahooFinanceService yahooService,
                               BacktestPersistenceService persistence,
                               StrategyOptimizer optimizer,
                               StrategyEnhancementService enhancementService,
                               BacktestRunRepository runRepo,
                               List<TradingStrategy> strategies) {
        this.backtestEngine = backtestEngine;
        this.candleRepo = candleRepo;
        this.csvImporter = csvImporter;
        this.yahooService = yahooService;
        this.persistence = persistence;
        this.optimizer = optimizer;
        this.enhancementService = enhancementService;
        this.runRepo = runRepo;
        this.strategiesByName = strategies.stream()
            .collect(Collectors.toMap(TradingStrategy::getName, s -> s));
    }

    // ── Data import ───────────────────────────────────────────────────────────

    @PostMapping("/fetch-yahoo")
    public String fetchYahoo(@RequestParam(defaultValue = "^NSEI") String yahooSymbol,
                              @RequestParam(defaultValue = "NIFTY") String symbol,
                              @RequestParam(defaultValue = "5m") String interval,
                              @RequestParam(defaultValue = "1mo") String range,
                              RedirectAttributes ra) {
        var result = yahooService.fetchAndStore(yahooSymbol, symbol, interval, range);
        if (result.isSuccess()) {
            ra.addFlashAttribute("importMessage", String.format(
                "Yahoo: fetched %d candles, saved %d new (%d duplicates) for %s [%s, %s]",
                result.fetched(), result.saved(), result.duplicates(), symbol, interval, range));
        } else {
            ra.addFlashAttribute("importError", result.error());
        }
        return "redirect:/backtest";
    }

    @GetMapping("/download-yahoo-csv")
    public ResponseEntity<String> downloadYahooCsv(
            @RequestParam(defaultValue = "^NSEI") String yahooSymbol,
            @RequestParam(defaultValue = "NIFTY") String symbol,
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "1mo") String range) {
        try {
            String csv = yahooService.fetchAsCsv(yahooSymbol, symbol, interval, range);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + symbol + "_" + interval + "_" + range + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().contentType(MediaType.TEXT_PLAIN)
                .body("Yahoo fetch failed: " + e.getMessage());
        }
    }

    @GetMapping("/import-csv")
    public String importCsvGet() { return "redirect:/backtest"; }

    @PostMapping("/import-csv")
    public String importCsv(@RequestParam("file") MultipartFile file,
                             @RequestParam(defaultValue = "NIFTY") String symbol,
                             @RequestParam(defaultValue = "5m") String timeframe,
                             RedirectAttributes ra) {
        if (file.isEmpty()) { ra.addFlashAttribute("importError", "No file selected"); return "redirect:/backtest"; }
        var result = csvImporter.importCsv(file, symbol, timeframe);
        ra.addFlashAttribute("importMessage", String.format(
            "Imported %d candles (%d duplicates skipped, %d parse errors) for %s [%s]",
            result.imported(), result.duplicates(), result.skipped(), symbol, timeframe));
        if (!result.errors().isEmpty())
            ra.addFlashAttribute("importError", String.join("; ", result.errors()));
        return "redirect:/backtest";
    }

    // ── Main page ─────────────────────────────────────────────────────────────

    @GetMapping
    public String backtestForm(@RequestParam(required = false) String strategyName, Model model) {
        model.addAttribute("strategies", strategiesByName.keySet());
        model.addAttribute("symbol", "NIFTY");
        model.addAttribute("defaultFromDate", LocalDate.now(IST).minusDays(30).toString());
        model.addAttribute("defaultToDate",   LocalDate.now(IST).toString());
        model.addAttribute("recentRuns", runRepo.findTop50ByOrderByRunAtDesc());
        if (strategyName != null) model.addAttribute("selectedStrategy", strategyName);
        return "backtest";
    }

    // ── Run backtest (saves to DB) ────────────────────────────────────────────

    @PostMapping("/run")
    public String runBacktest(@RequestParam String strategyName,
                               @RequestParam(defaultValue = "NIFTY") String symbol,
                               @RequestParam(defaultValue = "5m") String timeframe,
                               @RequestParam(defaultValue = "4") int windows,
                               @RequestParam(required = false) String fromDate,
                               @RequestParam(required = false) String toDate,
                               Model model) {
        TradingStrategy strategy = strategiesByName.get(strategyName);
        if (strategy == null) {
            model.addAttribute("error", "Unknown strategy: " + strategyName);
            model.addAttribute("strategies", strategiesByName.keySet());
            return "backtest";
        }

        LocalDate from = parseDateOrNull(fromDate);
        LocalDate to   = parseDateOrNull(toDate);
        var candles = loadCandles(symbol, timeframe, from, to);
        BacktestResult result = backtestEngine.runWalkForward(strategy, candles, windows);

        // Save to DB
        persistence.save(result, symbol, result.dataFrom(), result.dataTo(),
            strategy instanceof TunableStrategy ts ? ts.currentParams() : null,
            false, null);

        addResultToModel(model, result, strategyName, symbol, timeframe, windows, fromDate, toDate);
        model.addAttribute("recentRuns", runRepo.findTop50ByOrderByRunAtDesc());
        return "backtest";
    }

    // ── Optimize parameters (Part B) ─────────────────────────────────────────

    @PostMapping("/optimize")
    public String optimizeStrategy(@RequestParam String strategyName,
                                    @RequestParam(defaultValue = "NIFTY") String symbol,
                                    @RequestParam(defaultValue = "5m") String timeframe,
                                    @RequestParam(defaultValue = "4") int windows,
                                    @RequestParam(required = false) String fromDate,
                                    @RequestParam(required = false) String toDate,
                                    Model model) {
        TradingStrategy strategy = strategiesByName.get(strategyName);
        if (!(strategy instanceof TunableStrategy tunable)) {
            model.addAttribute("error", "Strategy " + strategyName + " does not support optimization.");
            model.addAttribute("strategies", strategiesByName.keySet());
            return "backtest";
        }

        LocalDate from = parseDateOrNull(fromDate);
        LocalDate to   = parseDateOrNull(toDate);
        var candles = loadCandles(symbol, timeframe, from, to);

        // Run optimizer — tries all param combinations
        OptimizationResult optResult = optimizer.optimize(tunable, candles, windows);

        // Save best result to DB
        persistence.save(optResult.bestResult(), symbol,
            optResult.bestResult().dataFrom(), optResult.bestResult().dataTo(),
            optResult.bestParams(), true,
            optResult.bestScore());

        // Save default result too (for comparison)
        persistence.save(optResult.defaultResult(), symbol,
            optResult.defaultResult().dataFrom(), optResult.defaultResult().dataTo(),
            optResult.defaultParams(), false, null);

        addResultToModel(model, optResult.bestResult(), strategyName, symbol, timeframe, windows, fromDate, toDate);
        model.addAttribute("optResult", optResult);
        model.addAttribute("recentRuns", runRepo.findTop50ByOrderByRunAtDesc());
        model.addAttribute("optimizerRuns",
            runRepo.findByStrategyNameAndIsOptimizedTrueOrderByOptimizerScoreDesc(strategyName));
        return "backtest";
    }

    // ── Claude AI Enhancement (Part C) ───────────────────────────────────────

    @PostMapping("/enhance")
    public String enhanceWithClaude(@RequestParam String strategyName,
                                     @RequestParam(defaultValue = "NIFTY") String symbol,
                                     @RequestParam(defaultValue = "5m") String timeframe,
                                     @RequestParam(defaultValue = "4") int windows,
                                     @RequestParam(required = false) String fromDate,
                                     @RequestParam(required = false) String toDate,
                                     Model model) {
        TradingStrategy strategy = strategiesByName.get(strategyName);
        if (!(strategy instanceof TunableStrategy tunable)) {
            model.addAttribute("error", "Strategy " + strategyName + " does not support optimization.");
            model.addAttribute("strategies", strategiesByName.keySet());
            return "backtest";
        }

        LocalDate from = parseDateOrNull(fromDate);
        LocalDate to   = parseDateOrNull(toDate);
        var candles = loadCandles(symbol, timeframe, from, to);

        // Step 1: optimize
        OptimizationResult optResult = optimizer.optimize(tunable, candles, windows);

        // Step 2: send to Claude
        StrategyEnhancementService.EnhancementSuggestion suggestion =
            enhancementService.analyse(optResult, optResult.bestResult());

        // Save best result
        persistence.save(optResult.bestResult(), symbol,
            optResult.bestResult().dataFrom(), optResult.bestResult().dataTo(),
            optResult.bestParams(), true, optResult.bestScore());

        addResultToModel(model, optResult.bestResult(), strategyName, symbol, timeframe, windows, fromDate, toDate);
        model.addAttribute("optResult", optResult);
        model.addAttribute("enhancement", suggestion);
        model.addAttribute("recentRuns", runRepo.findTop50ByOrderByRunAtDesc());
        return "backtest";
    }

    // ── History for one strategy ──────────────────────────────────────────────

    @GetMapping("/history/{strategyName}")
    @ResponseBody
    public List<BacktestRun> strategyHistory(@PathVariable String strategyName) {
        return runRepo.findByStrategyNameOrderByRunAtDesc(strategyName);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private java.util.List<com.niftyautotrader.model.Candle> loadCandles(
            String symbol, String timeframe, LocalDate from, LocalDate to) {
        ZonedDateTime f = from != null ? from.atStartOfDay(IST) : ZonedDateTime.now(IST).minusYears(10);
        ZonedDateTime t = to   != null ? to.plusDays(1).atStartOfDay(IST) : ZonedDateTime.now(IST).plusDays(1);
        return candleRepo.findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(symbol, timeframe, f, t);
    }

    private void addResultToModel(Model model, BacktestResult result, String strategyName,
                                   String symbol, String timeframe, int windows,
                                   String fromDate, String toDate) {
        model.addAttribute("result", result);
        model.addAttribute("strategies", strategiesByName.keySet());
        model.addAttribute("selectedStrategy", strategyName);
        model.addAttribute("symbol", symbol);
        model.addAttribute("selectedTimeframe", timeframe);
        model.addAttribute("selectedWindows", windows);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("defaultFromDate", LocalDate.now(IST).minusDays(30).toString());
        model.addAttribute("defaultToDate",   LocalDate.now(IST).toString());
    }

    private LocalDate parseDateOrNull(String s) {
        try { return (s != null && !s.isBlank()) ? LocalDate.parse(s) : null; }
        catch (Exception e) { return null; }
    }
}
