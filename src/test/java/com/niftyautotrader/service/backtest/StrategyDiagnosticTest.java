package com.niftyautotrader.service.backtest;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import com.niftyautotrader.service.strategy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("test")
class StrategyDiagnosticTest {

    @Autowired BacktestEngine engine;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void diagnoseSupertrendAndVwap() {
        List<Candle> candles = OptimizerIntegrationTest.syntheticCandles(30);
        System.out.println("\n=== Supertrend Diagnostic ===");

        // Build 15m candles from first 500 5m bars
        List<Candle> slice = candles.subList(0, Math.min(500, candles.size()));
        List<Candle> c15m = build15m(slice);
        System.out.println("5m bars: " + slice.size() + "  → 15m bars: " + c15m.size());

        double[] h = c15m.stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
        double[] l = c15m.stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
        double[] cl = c15m.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();

        var st = IndicatorUtils.supertrend(h, l, cl, 7, 1.5);
        int flips = 0;
        for (int i = 1; i < st.isBullish().length; i++) {
            if (st.isBullish()[i] != st.isBullish()[i - 1]) flips++;
        }
        System.out.println("Supertrend flips in " + c15m.size() + " 15m bars: " + flips);

        // ADX distribution
        int[] low = {0}, med = {0}, high = {0};
        for (int i = 50; i < slice.size(); i++) {
            double[] allH = slice.subList(0, i+1).stream().mapToDouble(c -> c.getHigh().doubleValue()).toArray();
            double[] allL = slice.subList(0, i+1).stream().mapToDouble(c -> c.getLow().doubleValue()).toArray();
            double[] allC = slice.subList(0, i+1).stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
            double adx = IndicatorUtils.adxLast(allH, allL, allC, 14);
            if (adx < 15) low[0]++;
            else if (adx < 25) med[0]++;
            else high[0]++;
        }
        System.out.printf("ADX distribution (bars 50-%d): <15: %d  15-25: %d  >25: %d%n",
            slice.size(), low[0], med[0], high[0]);

        System.out.println("\n=== VWAP/Bollinger Diagnostic ===");
        // Check RSI distribution
        double[] closes = slice.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] rsiArr = IndicatorUtils.rsi(closes, 14);
        long below35 = Arrays.stream(rsiArr).filter(r -> r > 0 && r < 35).count();
        long above65 = Arrays.stream(rsiArr).filter(r -> r > 65).count();
        long total = Arrays.stream(rsiArr).filter(r -> r > 0).count();
        System.out.printf("RSI on 5m candles: below 35: %d/%d (%.1f%%)  above 65: %d/%d (%.1f%%)%n",
            below35, total, 100.0 * below35 / total,
            above65, total, 100.0 * above65 / total);

        // Check ADX distribution for VWAP (needs adxMax)
        System.out.println("ADX > 40 blocks VWAP: " + high[0] + "/" + (low[0]+med[0]+high[0]));
    }

    private List<Candle> build15m(List<Candle> fiveM) {
        List<Candle> result = new ArrayList<>();
        for (int i = 0; i + 2 < fiveM.size(); i += 3) {
            Candle a = fiveM.get(i), b = fiveM.get(i + 1), c = fiveM.get(i + 2);
            Candle bar = new Candle();
            bar.setSymbol(a.getSymbol()); bar.setTimeframe("15m");
            bar.setOpenTime(a.getOpenTime()); bar.setOpen(a.getOpen());
            bar.setHigh(a.getHigh().max(b.getHigh()).max(c.getHigh()));
            bar.setLow(a.getLow().min(b.getLow()).min(c.getLow()));
            bar.setClose(c.getClose());
            bar.setVolume(a.getVolume() + b.getVolume() + c.getVolume());
            result.add(bar);
        }
        return result;
    }
}
