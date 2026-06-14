package com.niftyautotrader.service.strategy;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.model.Signal;
import com.niftyautotrader.model.SignalDirection;
import com.niftyautotrader.service.indicators.IndicatorUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class CandlestickPatternStrategy implements TunableStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ENTRY_START = LocalTime.of(9, 45);
    private static final LocalTime ENTRY_END   = LocalTime.of(14, 30);
    private static final String NAME = "CANDLESTICK_PATTERN";

    private enum PatternType {
        BULL_ENGULFING, BEAR_ENGULFING, HAMMER, SHOOTING_STAR,
        INSIDE_BAR_BULL, INSIDE_BAR_BEAR, NONE
    }

    private final double minWickRatio;
    private final double volumeMult;
    private final double rRRatio;
    private final double levelProxAtr;

    public CandlestickPatternStrategy() {
        this(2.0, 1.3, 2.0, 0.5);
    }

    private CandlestickPatternStrategy(double minWickRatio, double volumeMult,
                                        double rRRatio, double levelProxAtr) {
        this.minWickRatio  = minWickRatio;
        this.volumeMult    = volumeMult;
        this.rRRatio       = rRRatio;
        this.levelProxAtr  = levelProxAtr;
    }

    @Override public String getName() { return NAME; }

    @Override
    public Map<String, Double> currentParams() {
        return Map.of(
            "minWickRatio", minWickRatio,
            "volumeMult",   volumeMult,
            "rRRatio",      rRRatio,
            "levelProxAtr", levelProxAtr
        );
    }

    @Override
    public Map<String, double[]> paramGrid() {
        return Map.of(
            "minWickRatio",  new double[]{1.5, 2.0, 2.5},
            "volumeMult",    new double[]{1.0, 1.3, 1.5},
            "rRRatio",       new double[]{1.5, 2.0, 2.5},
            "levelProxAtr",  new double[]{0.3, 0.5, 0.8}
        );
    }

    @Override
    public TradingStrategy withParams(Map<String, Double> p) {
        return new CandlestickPatternStrategy(
            p.getOrDefault("minWickRatio", minWickRatio),
            p.getOrDefault("volumeMult",   volumeMult),
            p.getOrDefault("rRRatio",      rRRatio),
            p.getOrDefault("levelProxAtr", levelProxAtr)
        );
    }

    @Override
    public Optional<Signal> evaluate(MarketContext ctx) {
        LocalTime now = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalTime();
        if (now.isBefore(ENTRY_START) || now.isAfter(ENTRY_END)) return Optional.empty();

        List<Candle> candles = ctx.candles5m();
        if (candles.size() < 25) return Optional.empty();

        LocalDate today = ctx.evaluatedAt().withZoneSameInstant(IST).toLocalDate();
        double atr = ctx.currentAtr();
        if (atr <= 0) return Optional.empty();

        Candle curr  = candles.get(candles.size() - 1);
        Candle prev1 = candles.get(candles.size() - 2);
        Candle prev2 = candles.get(candles.size() - 3);

        LocalDate currDate = curr.getOpenTime().withZoneSameInstant(IST).toLocalDate();
        if (!currDate.equals(today)) return Optional.empty();

        PatternType pattern = detectPattern(prev2, prev1, curr, atr);
        if (pattern == PatternType.NONE) return Optional.empty();

        boolean isBull = pattern == PatternType.BULL_ENGULFING
                      || pattern == PatternType.HAMMER
                      || pattern == PatternType.INSIDE_BAR_BULL;

        if (!isTrendAligned(pattern, candles)) return Optional.empty();

        double price = curr.getClose().doubleValue();
        double vwap  = ctx.currentVwap().doubleValue();

        if (isBull && price < vwap) return Optional.empty();
        if (!isBull && price > vwap) return Optional.empty();

        double[] pdhl = findPdHighLow(candles, today);
        double pdh = pdhl[0];
        double pdl = pdhl[1];

        List<Candle> todayCandles = candles.stream()
            .filter(c -> c.getOpenTime().withZoneSameInstant(IST).toLocalDate().equals(today))
            .toList();

        if (!isNearKeyLevel(price, vwap, pdh, pdl, atr, todayCandles)) return Optional.empty();

        double avgVol = avgVolume(candles, 20);
        if (avgVol > 0 && curr.getVolume() < avgVol * volumeMult) return Optional.empty();

        double[] closes = candles.stream()
            .mapToDouble(c -> c.getClose().doubleValue())
            .toArray();
        double rsi = IndicatorUtils.rsiLast(closes, 14);

        if (isBull  && (rsi < 35 || rsi > 70)) return Optional.empty();
        if (!isBull && (rsi < 30 || rsi > 65)) return Optional.empty();

        if (hasRecentPattern(candles, 10)) return Optional.empty();

        double sl;
        if (pattern == PatternType.BULL_ENGULFING) {
            sl = curr.getLow().doubleValue() - 0.05 * atr;
        } else if (pattern == PatternType.BEAR_ENGULFING) {
            sl = curr.getHigh().doubleValue() + 0.05 * atr;
        } else if (pattern == PatternType.HAMMER) {
            sl = curr.getLow().doubleValue() - 0.05 * atr;
        } else if (pattern == PatternType.SHOOTING_STAR) {
            sl = curr.getHigh().doubleValue() + 0.05 * atr;
        } else if (pattern == PatternType.INSIDE_BAR_BULL) {
            sl = curr.getLow().doubleValue() - 0.05 * atr;
        } else {
            sl = curr.getHigh().doubleValue() + 0.05 * atr;
        }

        double risk = Math.abs(price - sl);
        double tgt  = isBull ? price + rRRatio * risk : price - rRRatio * risk;

        SignalDirection dir = isBull ? SignalDirection.LONG_CE : SignalDirection.LONG_PE;
        String trend = isBull ? "UP" : "DOWN";
        String reason = String.format(
            "%s near VWAP(%.0f) vol=%.1fx RSI=%.0f trend=%s price=%.0f",
            pattern.name(), vwap, (avgVol > 0 ? curr.getVolume() / avgVol : 0), rsi, trend, price
        );

        return Optional.of(buildSignal(ctx, dir, price, sl, tgt, reason));
    }

    private PatternType detectPattern(Candle prev2, Candle prev1, Candle curr, double atr) {
        double p1O = prev1.getOpen().doubleValue();
        double p1C = prev1.getClose().doubleValue();
        double p1H = prev1.getHigh().doubleValue();
        double p1L = prev1.getLow().doubleValue();

        double cO = curr.getOpen().doubleValue();
        double cC = curr.getClose().doubleValue();
        double cH = curr.getHigh().doubleValue();
        double cL = curr.getLow().doubleValue();

        boolean p1Red   = p1C < p1O;
        boolean p1Green = p1C > p1O;
        boolean cGreen  = cC > cO;
        boolean cRed    = cC < cO;

        if (p1Red && cGreen && cO < p1C && cC > p1O) {
            return PatternType.BULL_ENGULFING;
        }

        if (p1Green && cRed && cO > p1C && cC < p1O) {
            return PatternType.BEAR_ENGULFING;
        }

        double cBody      = Math.abs(cC - cO);
        double cLowerWick = Math.min(cO, cC) - cL;
        double cUpperWick = cH - Math.max(cO, cC);
        double minBody    = curr.getClose().doubleValue() * 0.0003;

        if (cBody >= minBody
                && cLowerWick >= minWickRatio * cBody
                && cUpperWick <= 0.3 * cBody) {
            double p2C = prev2.getClose().doubleValue();
            double p1Cl = prev1.getClose().doubleValue();
            boolean decliningPrior = p1Cl < p2C && p1Cl < prev1.getOpen().doubleValue();
            if (decliningPrior) return PatternType.HAMMER;
        }

        if (cBody >= minBody
                && cUpperWick >= minWickRatio * cBody
                && cLowerWick <= 0.3 * cBody) {
            double p2C = prev2.getClose().doubleValue();
            double p1Cl = prev1.getClose().doubleValue();
            boolean risingPrior = p1Cl > p2C && p1Cl > prev1.getOpen().doubleValue();
            if (risingPrior) return PatternType.SHOOTING_STAR;
        }

        boolean contained = cH < p1H && cL > p1L;
        if (contained) {
            double midpoint = (p1H + p1L) / 2.0;
            if (cC > midpoint) return PatternType.INSIDE_BAR_BULL;
            if (cC < midpoint) return PatternType.INSIDE_BAR_BEAR;
        }

        return PatternType.NONE;
    }

    private boolean isTrendAligned(PatternType pattern, List<Candle> candles) {
        boolean isBull = pattern == PatternType.BULL_ENGULFING
                      || pattern == PatternType.HAMMER
                      || pattern == PatternType.INSIDE_BAR_BULL;

        int n = candles.size();
        if (n < 36) return true;

        double[] closes = candles.stream()
            .mapToDouble(c -> c.getClose().doubleValue())
            .toArray();

        double[] ema21 = IndicatorUtils.ema(closes, 21);
        double emaLast = ema21[n - 1];
        double ema15ago = ema21[n - 16];

        boolean uptrend = emaLast > ema15ago;
        return isBull == uptrend;
    }

    private boolean isNearKeyLevel(double price, double vwap, double pdh, double pdl,
                                    double atr, List<Candle> todayCandles) {
        double band = levelProxAtr * atr;

        if (Math.abs(price - vwap) <= band) return true;
        if (pdh > 0 && Math.abs(price - pdh) <= band) return true;
        if (pdl > 0 && Math.abs(price - pdl) <= band) return true;

        double round100 = Math.round(price / 100.0) * 100.0;
        if (Math.abs(price - round100) <= band) return true;

        double round500 = Math.round(price / 500.0) * 500.0;
        if (Math.abs(price - round500) <= band) return true;

        if (!todayCandles.isEmpty()) {
            double intradayHigh = todayCandles.stream()
                .mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(0);
            double intradayLow = todayCandles.stream()
                .mapToDouble(c -> c.getLow().doubleValue()).min().orElse(0);
            if (Math.abs(price - intradayHigh) <= band) return true;
            if (Math.abs(price - intradayLow) <= band) return true;
        }

        return false;
    }

    private double[] findPdHighLow(List<Candle> candles, LocalDate today) {
        double pdh = 0, pdl = 0;
        LocalDate prevDay = null;
        for (Candle c : candles) {
            LocalDate cd = c.getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (cd.equals(today)) continue;
            if (prevDay == null || cd.isAfter(prevDay)) prevDay = cd;
        }
        if (prevDay == null) return new double[]{0, 0};
        final LocalDate pd = prevDay;
        double high = Double.MIN_VALUE, low = Double.MAX_VALUE;
        boolean found = false;
        for (Candle c : candles) {
            LocalDate cd = c.getOpenTime().withZoneSameInstant(IST).toLocalDate();
            if (!cd.equals(pd)) continue;
            high = Math.max(high, c.getHigh().doubleValue());
            low  = Math.min(low,  c.getLow().doubleValue());
            found = true;
        }
        return found ? new double[]{high, low} : new double[]{0, 0};
    }

    private double avgVolume(List<Candle> candles, int lookback) {
        int n = candles.size();
        int from = Math.max(0, n - lookback - 1);
        int to   = n - 1;
        if (to <= from) return 0;
        long sum = 0;
        int count = 0;
        for (int i = from; i < to; i++) {
            sum += candles.get(i).getVolume();
            count++;
        }
        return count == 0 ? 0 : (double) sum / count;
    }

    private boolean hasRecentPattern(List<Candle> candles, int lookbackBars) {
        int n = candles.size();
        if (n < lookbackBars + 3) return false;
        int checkFrom = n - 1 - lookbackBars;
        int checkTo   = n - 2;
        for (int i = checkFrom + 2; i <= checkTo; i++) {
            Candle p2 = candles.get(i - 2);
            Candle p1 = candles.get(i - 1);
            Candle c  = candles.get(i);
            double mockAtr = Math.abs(c.getHigh().doubleValue() - c.getLow().doubleValue()) * 1.5;
            if (mockAtr <= 0) mockAtr = 1;
            if (detectPattern(p2, p1, c, mockAtr) != PatternType.NONE) return true;
        }
        return false;
    }

    private Signal buildSignal(MarketContext ctx, SignalDirection dir, double entry,
                                double sl, double target, String reason) {
        Signal s = new Signal();
        s.setGeneratedAt(ZonedDateTime.now());
        s.setStrategyName(getName());
        s.setSymbol(ctx.symbol());
        s.setDirection(dir);
        s.setSuggestedEntry(BigDecimal.valueOf(entry));
        s.setSuggestedStopLoss(BigDecimal.valueOf(sl));
        s.setSuggestedTarget(BigDecimal.valueOf(target));
        s.setLots(1);
        s.setReasoning(reason);
        return s;
    }
}
