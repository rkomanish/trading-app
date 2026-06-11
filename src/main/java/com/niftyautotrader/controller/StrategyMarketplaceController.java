package com.niftyautotrader.controller;

import com.niftyautotrader.service.backtest.StrategyMarketplaceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/strategies")
public class StrategyMarketplaceController {

    private final StrategyMarketplaceService marketplaceService;

    public StrategyMarketplaceController(StrategyMarketplaceService marketplaceService) {
        this.marketplaceService = marketplaceService;
    }

    @GetMapping
    public String marketplace(
            @RequestParam(required = false) String type,
            Model model) {
        var cards = marketplaceService.buildCards();

        // Optional client-side filter by strategy type
        if (type != null && !type.isBlank()) {
            cards = cards.stream()
                .filter(c -> c.strategyType().equalsIgnoreCase(type))
                .toList();
            model.addAttribute("activeFilter", type);
        }

        model.addAttribute("cards", cards);
        model.addAttribute("totalStrategies", cards.size());
        return "strategies";
    }
}
