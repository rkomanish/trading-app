package com.niftyautotrader.controller;

import com.niftyautotrader.broker.Broker;
import com.niftyautotrader.service.paper.PaperBroker;
import com.niftyautotrader.repository.SignalRepository;
import com.niftyautotrader.service.strategy.StrategyEngine;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/paper")
public class PaperTradingController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Broker broker;
    private final StrategyEngine strategyEngine;
    private final SignalRepository signalRepo;

    public PaperTradingController(Broker broker,
                                   StrategyEngine strategyEngine,
                                   SignalRepository signalRepo) {
        this.broker = broker;
        this.strategyEngine = strategyEngine;
        this.signalRepo = signalRepo;
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("isPaper", broker.isPaper());
        model.addAttribute("brokerName", broker.getName());
        model.addAttribute("currentRegime", strategyEngine.getLastRegime());
        model.addAttribute("activeStrategies", strategyEngine.getLastActiveStrategies());

        Map<String, Integer> positions = broker.getOpenPositions();
        model.addAttribute("positions", positions);
        model.addAttribute("hasPositions", !positions.isEmpty());

        // Current prices for open positions
        if (!positions.isEmpty()) {
            Map<String, BigDecimal> prices = broker.getLastPrices(List.copyOf(positions.keySet()));
            model.addAttribute("prices", prices);

            if (broker instanceof PaperBroker pb) {
                model.addAttribute("entryPrices", pb.getEntryPrices());
                model.addAttribute("realizedPnl", pb.getRealizedPnl());
                model.addAttribute("totalPaperTrades", pb.getTotalPaperTrades());

                // Unrealized P&L
                BigDecimal unrealizedPnl = BigDecimal.ZERO;
                for (Map.Entry<String, Integer> pos : positions.entrySet()) {
                    BigDecimal entry  = pb.getEntryPrices().getOrDefault(pos.getKey(), BigDecimal.ZERO);
                    BigDecimal current = prices.getOrDefault(pos.getKey(), BigDecimal.ZERO);
                    unrealizedPnl = unrealizedPnl.add(
                        current.subtract(entry).multiply(BigDecimal.valueOf(pos.getValue()))
                    );
                }
                model.addAttribute("unrealizedPnl", unrealizedPnl);
            }
        } else {
            if (broker instanceof PaperBroker pb) {
                model.addAttribute("realizedPnl", pb.getRealizedPnl());
                model.addAttribute("totalPaperTrades", pb.getTotalPaperTrades());
            }
            model.addAttribute("unrealizedPnl", BigDecimal.ZERO);
        }

        // Today's signals
        ZonedDateTime todayStart = ZonedDateTime.now(IST).toLocalDate().atStartOfDay(IST);
        var todaySignals = signalRepo.findAll().stream()
            .filter(s -> s.getGeneratedAt() != null && s.getGeneratedAt().isAfter(todayStart))
            .sorted((a, b) -> b.getGeneratedAt().compareTo(a.getGeneratedAt()))
            .limit(20)
            .toList();
        model.addAttribute("todaySignals", todaySignals);

        return "paper-dashboard";
    }
}
