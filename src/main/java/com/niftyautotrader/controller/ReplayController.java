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
                                           @RequestParam(defaultValue = "5m") String timeframe) {
        return replayService.dayCandles(LocalDate.parse(date), timeframe);
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────────

    @PostMapping("/start")
    @ResponseBody
    public Map<String, Object> start(@RequestBody Map<String, Object> body, HttpSession http) {
        LocalDate date = LocalDate.parse((String) body.get("date"));
        String tf = (String) body.getOrDefault("timeframe", "5m");
        BigDecimal capital = new BigDecimal(String.valueOf(body.getOrDefault("capital", "100000")));
        ReplaySession s = replayService.startSession(http.getId(), date, tf, capital);
        return state(s, null);
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
        s.open(side, lots, price, time);
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
