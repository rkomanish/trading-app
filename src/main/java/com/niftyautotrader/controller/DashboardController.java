package com.niftyautotrader.controller;

import com.niftyautotrader.broker.Broker;
import com.niftyautotrader.config.RiskProperties;
import com.niftyautotrader.config.TradingProperties;
import com.niftyautotrader.repository.*;
import com.niftyautotrader.service.auth.KiteAuthService;
import com.niftyautotrader.service.execution.OrderExecutionService;
import com.niftyautotrader.service.risk.RiskEngine;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@Controller
public class DashboardController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final RiskEngine riskEngine;
    private final OrderExecutionService executionService;
    private final TradeRepository tradeRepo;
    private final SignalRepository signalRepo;
    private final RiskEventRepository riskEventRepo;
    private final SentimentSnapshotRepository sentimentRepo;
    private final DailyPnlRepository dailyPnlRepo;
    private final Broker broker;
    private final KiteAuthService authService;
    private final TradingProperties tradingProps;
    private final RiskProperties riskProps;

    public DashboardController(RiskEngine riskEngine,
                                OrderExecutionService executionService,
                                TradeRepository tradeRepo,
                                SignalRepository signalRepo,
                                RiskEventRepository riskEventRepo,
                                SentimentSnapshotRepository sentimentRepo,
                                DailyPnlRepository dailyPnlRepo,
                                Broker broker,
                                KiteAuthService authService,
                                TradingProperties tradingProps,
                                RiskProperties riskProps) {
        this.riskEngine = riskEngine;
        this.executionService = executionService;
        this.tradeRepo = tradeRepo;
        this.signalRepo = signalRepo;
        this.riskEventRepo = riskEventRepo;
        this.sentimentRepo = sentimentRepo;
        this.dailyPnlRepo = dailyPnlRepo;
        this.broker = broker;
        this.authService = authService;
        this.tradingProps = tradingProps;
        this.riskProps = riskProps;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        var state = riskEngine.getState();
        model.addAttribute("killSwitchActive", state.isKillSwitchActive());
        model.addAttribute("autoTradingEnabled", state.isAutoTradingEnabled());
        model.addAttribute("weeklyBreakerActive", state.isWeeklyCircuitBreakerActive());
        model.addAttribute("tradesUsedToday", state.getTradesPlacedToday());
        model.addAttribute("maxTradesPerDay", riskProps.getMaxTradesPerDay());
        model.addAttribute("dailyPnl", state.getDailyRealizedLoss());
        model.addAttribute("maxDailyLoss", riskProps.getMaxDailyLoss());
        model.addAttribute("liveMode", tradingProps.isLiveEnabled());
        model.addAttribute("brokerName", broker.getName());
        model.addAttribute("kiteConnected", authService.isConnected());
        model.addAttribute("kiteStatus", authService.getConnectionStatus());
        model.addAttribute("openPositions", broker.getOpenPositions());
        model.addAttribute("openTrades", tradeRepo.findByExitTimeIsNullOrderByEntryTimeDesc());
        model.addAttribute("recentRiskEvents", riskEventRepo.findTop20ByOrderByOccurredAtDesc());
        model.addAttribute("latestSentiment", sentimentRepo.findTopByOrderByCapturedAtDesc().orElse(null));
        model.addAttribute("recentDailyPnl", dailyPnlRepo.findTop10ByOrderByTradingDateDesc());
        model.addAttribute("now", ZonedDateTime.now(IST));
        return "dashboard";
    }

    @GetMapping("/trades")
    public String trades(Model model) {
        model.addAttribute("trades", tradeRepo.findTop50ByOrderByEntryTimeDesc());
        model.addAttribute("signals", signalRepo.findTop50ByOrderByGeneratedAtDesc());
        return "trades";
    }

    @GetMapping("/sentiment")
    public String sentiment(Model model) {
        model.addAttribute("snapshots", sentimentRepo.findTop20ByOrderByCapturedAtDesc());
        return "sentiment";
    }

    @GetMapping("/config")
    public String config(Model model) {
        model.addAttribute("riskProps", riskProps);
        model.addAttribute("tradingProps", tradingProps);
        model.addAttribute("hardMaxLots", RiskProperties.HARD_MAX_LOTS);
        model.addAttribute("hardMaxTrades", RiskProperties.HARD_MAX_TRADES_PER_DAY);
        model.addAttribute("lotSize", RiskProperties.NIFTY_LOT_SIZE);
        return "config";
    }

    @GetMapping("/auth/kite")
    public String kiteLogin() {
        return "redirect:" + authService.buildLoginUrl();
    }

    @GetMapping("/auth/kite/callback")
    public String kiteCallback(@RequestParam(required = false) String request_token,
                                @RequestParam(required = false) String action,
                                RedirectAttributes ra) {
        if ("login".equals(action) && request_token != null) {
            boolean ok = authService.exchangeRequestToken(request_token);
            ra.addFlashAttribute("message", ok ? "Kite connected successfully" : "Kite auth failed");
        }
        return "redirect:/dashboard";
    }

    // ─── Kill switch endpoint ─────────────────────────────────────────────────

    @PostMapping("/kill-switch")
    public String activateKillSwitch(@RequestParam(defaultValue = "Manual activation") String reason,
                                      RedirectAttributes ra) {
        executionService.executeKillSwitch(reason);
        ra.addFlashAttribute("killSwitchMessage",
            "KILL SWITCH ACTIVATED — all positions flattened. Reason: " + reason);
        return "redirect:/dashboard";
    }

    @PostMapping("/kill-switch/reset")
    public String resetKillSwitch(RedirectAttributes ra) {
        // Reset only allowed manually; auto-trading NOT re-enabled automatically
        riskEngine.getState().resetWeeklyCircuitBreaker();
        ra.addFlashAttribute("message",
            "Kill switch reset. Auto-trading remains OFF — re-enable manually.");
        return "redirect:/dashboard";
    }
}
