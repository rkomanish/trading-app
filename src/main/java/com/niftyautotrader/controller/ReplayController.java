package com.niftyautotrader.controller;

import com.niftyautotrader.service.replay.ReplayService;
import com.niftyautotrader.service.replay.ReplaySession;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Market Replay — a self-contained demo backtesting simulator for Nifty 50.
 *
 * Lets a user "travel back" to any past trading day, watch candles reveal
 * progressively as if the market were live, and buy/sell with demo money.
 * Completely isolated from live trading: no RiskEngine, no PaperBroker, no broker.
 */
@Controller
@RequestMapping("/replay")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    // ── Page ────────────────────────────────────────────────────────────────────

    @GetMapping
    public String page(Model model) {
        model.addAttribute("availableDates", replayService.availableDates());
        model.addAttribute("timeframes", ReplayService.TIMEFRAMES);
        return "replay";
    }

    // ── Data ──────────────────────────────────────────────────────────────────────

    @GetMapping("/dates")
    @ResponseBody
    public List<LocalDate> dates() {
        return replayService.availableDates();
    }

    @GetMapping("/candles")
    @ResponseBody
    public List<ReplayService.Bar> candles(@RequestParam String date,
                                           @RequestParam(defaultValue = "5m") String timeframe,
                                           @RequestParam(defaultValue = "0") int contextDays) {
        return replayService.dayCandles(LocalDate.parse(date), timeframe, contextDays);
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────────

    @PostMapping("/start")
    @ResponseBody
    public Map<String, Object> start(@RequestBody Map<String, Object> body, HttpSession http) {
        LocalDate date = LocalDate.parse((String) body.get("date"));
        String tf = (String) body.getOrDefault("timeframe", "5m");
        BigDecimal capital = new BigDecimal(String.valueOf(body.getOrDefault("capital", "100000")));
        double ivPct = Double.parseDouble(String.valueOf(body.getOrDefault("iv", "12")));
        ReplaySession s = replayService.startSession(http.getId(), date, tf, capital, ivPct / 100.0);
        return state(s, null);
    }

    /** Synthetic Black-Scholes option chain around the given spot. */
    @GetMapping("/chain")
    @ResponseBody
    public Map<String, Object> chain(@RequestParam double spot,
                                     @RequestParam(defaultValue = "50") int step,
                                     @RequestParam(defaultValue = "10") int strikes,
                                     HttpSession http) {
        ReplaySession s = replayService.session(http.getId());
        double iv = s != null ? s.getIv() : 0.12;
        double tte = s != null ? s.getTteYears() : 7.0 / 365.0;
        Map<String, Object> m = new HashMap<>();
        m.put("spot", spot);
        m.put("ivPct", iv * 100.0);
        m.put("daysToExpiry", Math.round(tte * 365.0));
        m.put("rows", replayService.buildChain(spot, iv, tte, step, strikes));
        return m;
    }

    /** Buy/sell a CE/PE from the chain — entry premium is priced from spot. */
    @PostMapping("/option-order")
    @ResponseBody
    public Map<String, Object> optionOrder(@RequestBody Map<String, Object> body, HttpSession http) {
        ReplaySession s = require(http);
        String optType = String.valueOf(body.get("optType")); // CE or PE
        String side = String.valueOf(body.getOrDefault("side", "LONG"));
        double strike = ((Number) body.get("strike")).doubleValue();
        int lots = ((Number) body.getOrDefault("lots", 1)).intValue();
        double spot = ((Number) body.get("spot")).doubleValue();
        String time = String.valueOf(body.getOrDefault("time", ""));
        if (lots < 1) lots = 1;
        double premium = com.niftyautotrader.service.replay.BlackScholes
            .price(spot, strike, s.getTteYears(), s.getIv(), "CE".equals(optType)).price();
        s.openOption(optType, strike, side, lots, BigDecimal.valueOf(Math.round(premium * 100.0) / 100.0),
            time, num(body.get("sl")), num(body.get("target")));
        return state(s, BigDecimal.valueOf(spot));
    }

    @PostMapping("/order")
    @ResponseBody
    public Map<String, Object> order(@RequestBody Map<String, Object> body, HttpSession http) {
        ReplaySession s = require(http);
        String side = String.valueOf(body.get("side"));          // LONG or SHORT
        int lots = ((Number) body.getOrDefault("lots", 1)).intValue();
        BigDecimal price = new BigDecimal(String.valueOf(body.get("price")));
        String time = String.valueOf(body.getOrDefault("time", ""));
        if (lots < 1) lots = 1;
        s.open(side, lots, price, time, num(body.get("sl")), num(body.get("target")));
        return state(s, price);
    }

    @PostMapping("/close")
    @ResponseBody
    public Map<String, Object> close(@RequestBody Map<String, Object> body, HttpSession http) {
        ReplaySession s = require(http);
        long posId = ((Number) body.get("posId")).longValue();
        BigDecimal price = new BigDecimal(String.valueOf(body.get("price")));
        String time = String.valueOf(body.getOrDefault("time", ""));
        s.close(posId, price, time);
        return state(s, price);
    }

    @PostMapping("/squareoff")
    @ResponseBody
    public Map<String, Object> squareOff(@RequestBody Map<String, Object> body, HttpSession http) {
        ReplaySession s = require(http);
        BigDecimal price = new BigDecimal(String.valueOf(body.get("price")));
        String time = String.valueOf(body.getOrDefault("time", ""));
        s.squareOffAll(price, time);
        return state(s, price);
    }

    @GetMapping("/state")
    @ResponseBody
    public Map<String, Object> currentState(@RequestParam(required = false) String price, HttpSession http) {
        ReplaySession s = replayService.session(http.getId());
        if (s == null) return Map.of("active", false);
        return state(s, price != null ? new BigDecimal(price) : null);
    }

    @PostMapping("/reset")
    @ResponseBody
    public Map<String, Object> reset(HttpSession http) {
        replayService.reset(http.getId());
        return Map.of("active", false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** Parse a nullable/blank numeric body value into a BigDecimal (null if absent/blank). */
    private BigDecimal num(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private ReplaySession require(HttpSession http) {
        ReplaySession s = replayService.session(http.getId());
        if (s == null) throw new IllegalStateException("No active replay session — start one first.");
        return s;
    }

    private Map<String, Object> state(ReplaySession s, BigDecimal markPrice) {
        BigDecimal unrealized = markPrice != null ? s.unrealized(markPrice) : BigDecimal.ZERO;
        BigDecimal equity = s.getStartingCapital().add(s.getRealizedPnl()).add(unrealized);

        Map<String, Object> m = new HashMap<>();
        m.put("active", true);
        m.put("date", s.getDate().toString());
        m.put("timeframe", s.getTimeframe());
        m.put("startingCapital", s.getStartingCapital());
        m.put("realizedPnl", s.getRealizedPnl());
        m.put("unrealizedPnl", unrealized);
        m.put("equity", equity);
        m.put("openLots", s.getOpenLots());
        m.put("positions", s.getPositions());
        m.put("trades", s.getTrades());
        return m;
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseBody
    public Map<String, Object> handle(IllegalStateException e) {
        return Map.of("active", false, "error", e.getMessage());
    }
}
