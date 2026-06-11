package com.niftyautotrader.controller;

import com.niftyautotrader.repository.CandleRepository;
import com.niftyautotrader.service.backtest.BacktestEngine;
import com.niftyautotrader.service.backtest.BacktestResult;
import com.niftyautotrader.service.backtest.CandleCsvImporter;
import com.niftyautotrader.service.marketdata.YahooFinanceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
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
    private final CandleCsvImporter csvImporter;
    private final YahooFinanceService yahooService;
    private final Map<String, TradingStrategy> strategiesByName;

    public BacktestController(BacktestEngine backtestEngine,
                               CandleRepository candleRepo,
                               CandleCsvImporter csvImporter,
                               YahooFinanceService yahooService,
                               List<TradingStrategy> strategies) {
        this.backtestEngine = backtestEngine;
        this.candleRepo = candleRepo;
        this.csvImporter = csvImporter;
        this.yahooService = yahooService;
        this.strategiesByName = strategies.stream()
            .collect(Collectors.toMap(TradingStrategy::getName, s -> s));
    }

    /** Fetch last month of candles from Yahoo Finance straight into the DB. */
    @PostMapping("/fetch-yahoo")
    public String fetchYahoo(@RequestParam(defaultValue = "^NSEI") String yahooSymbol,
                              @RequestParam(defaultValue = "NIFTY") String symbol,
                              @RequestParam(defaultValue = "5m") String interval,
                              @RequestParam(defaultValue = "1mo") String range,
                              RedirectAttributes ra) {
        var result = yahooService.fetchAndStore(yahooSymbol, symbol, interval, range);
        if (result.isSuccess()) {
            ra.addFlashAttribute("importMessage", String.format(
                "Yahoo: fetched %d candles, saved %d new (%d already existed) for %s [%s, %s]",
                result.fetched(), result.saved(), result.duplicates(), symbol, interval, range));
        } else {
            ra.addFlashAttribute("importError", result.error());
        }
        return "redirect:/backtest";
    }

    /** Download last month of Yahoo candles as a CSV file in our import format. */
    @GetMapping("/download-yahoo-csv")
    public ResponseEntity<String> downloadYahooCsv(
            @RequestParam(defaultValue = "^NSEI") String yahooSymbol,
            @RequestParam(defaultValue = "NIFTY") String symbol,
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "1mo") String range) {
        try {
            String csv = yahooService.fetchAsCsv(yahooSymbol, symbol, interval, range);
            String filename = String.format("%s_%s_%s.csv", symbol, interval, range);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .contentType(MediaType.TEXT_PLAIN)
                .body("Yahoo fetch failed: " + e.getMessage());
        }
    }

    @GetMapping("/import-csv")
    public String importCsvGet() {
        return "redirect:/backtest";
    }

    @PostMapping("/import-csv")
    public String importCsv(@RequestParam("file") MultipartFile file,
                             @RequestParam(defaultValue = "NIFTY") String symbol,
                             @RequestParam(defaultValue = "5m") String timeframe,
                             RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("importError", "No file selected");
            return "redirect:/backtest";
        }
        var result = csvImporter.importCsv(file, symbol, timeframe);
        ra.addFlashAttribute("importMessage", String.format(
            "Imported %d candles (%d duplicates skipped, %d parse errors) for %s [%s]",
            result.imported(), result.duplicates(), result.skipped(), symbol, timeframe));
        if (!result.errors().isEmpty()) {
            ra.addFlashAttribute("importError", String.join("; ", result.errors()));
        }
        return "redirect:/backtest";
    }

    @GetMapping
    public String backtestForm(@RequestParam(required = false) String strategyName,
                               Model model) {
        model.addAttribute("strategies", strategiesByName.keySet());
        model.addAttribute("symbol", "NIFTY");
        if (strategyName != null) {
            model.addAttribute("selectedStrategy", strategyName);
        }
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

        ZonedDateTime cutoff = ZonedDateTime.now(IST).minusYears(10);
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
