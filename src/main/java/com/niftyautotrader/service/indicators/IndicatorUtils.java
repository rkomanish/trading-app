package com.niftyautotrader.service.indicators;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure-function technical indicator library.
 * All methods are stateless and take immutable input lists.
 * Input lists must be in chronological order (oldest first).
 */
public final class IndicatorUtils {

    private IndicatorUtils() {}

    // ─── EMA ─────────────────────────────────────────────────────────────────

    /**
     * Compute EMA series over closing prices. Returns list of same length as input;
     * first (period-1) values are seeded with SMA.
     */
    public static double[] ema(double[] closes, int period) {
        if (closes.length < period) return new double[closes.length];
        double[] result = new double[closes.length];
        double multiplier = 2.0 / (period + 1);

        // Seed with SMA of first period
        double sum = 0;
        for (int i = 0; i < period; i++) sum += closes[i];
        result[period - 1] = sum / period;

        for (int i = period; i < closes.length; i++) {
            result[i] = closes[i] * multiplier + result[i - 1] * (1 - multiplier);
        }
        return result;
    }

    /** Returns only the final (latest) EMA value. */
    public static double emaLast(double[] closes, int period) {
        double[] series = ema(closes, period);
        return series[series.length - 1];
    }

    // ─── RSI ─────────────────────────────────────────────────────────────────

    /**
     * Wilder's RSI (14-period standard). Returns array same length as input.
     * Values at indices < period will be 0.
     */
    public static double[] rsi(double[] closes, int period) {
        double[] result = new double[closes.length];
        if (closes.length <= period) return result;

        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) avgGain += change;
            else avgLoss -= change;
        }
        avgGain /= period;
        avgLoss /= period;
        result[period] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));

        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            result[i] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));
        }
        return result;
    }

    public static double rsiLast(double[] closes, int period) {
        double[] series = rsi(closes, period);
        return series[series.length - 1];
    }

    // ─── ATR ─────────────────────────────────────────────────────────────────

    /**
     * Average True Range (Wilder's smoothing). Returns array of same length.
     */
    public static double[] atr(double[] highs, double[] lows, double[] closes, int period) {
        int n = highs.length;
        double[] tr = new double[n];
        double[] result = new double[n];
        if (n < 2) return result;

        tr[0] = highs[0] - lows[0];
        for (int i = 1; i < n; i++) {
            double hl = highs[i] - lows[i];
            double hpc = Math.abs(highs[i] - closes[i - 1]);
            double lpc = Math.abs(lows[i] - closes[i - 1]);
            tr[i] = Math.max(hl, Math.max(hpc, lpc));
        }

        // Seed with SMA
        if (n < period) return result;
        double atrVal = 0;
        for (int i = 0; i < period; i++) atrVal += tr[i];
        atrVal /= period;
        result[period - 1] = atrVal;

        for (int i = period; i < n; i++) {
            atrVal = (atrVal * (period - 1) + tr[i]) / period;
            result[i] = atrVal;
        }
        return result;
    }

    public static double atrLast(double[] highs, double[] lows, double[] closes, int period) {
        double[] series = atr(highs, lows, closes, period);
        return series[series.length - 1];
    }

    // ─── VWAP ────────────────────────────────────────────────────────────────

    /**
     * Session VWAP. Resets each trading day — caller must pass only intraday candles.
     * Returns VWAP up to each bar.
     */
    public static double[] vwap(double[] highs, double[] lows, double[] closes, long[] volumes) {
        int n = highs.length;
        double[] result = new double[n];
        double cumTpv = 0;
        long cumVol = 0;
        for (int i = 0; i < n; i++) {
            double typicalPrice = (highs[i] + lows[i] + closes[i]) / 3.0;
            cumTpv += typicalPrice * volumes[i];
            cumVol += volumes[i];
            result[i] = cumVol == 0 ? typicalPrice : cumTpv / cumVol;
        }
        return result;
    }

    public static double vwapLast(double[] highs, double[] lows, double[] closes, long[] volumes) {
        double[] series = vwap(highs, lows, closes, volumes);
        return series[series.length - 1];
    }

    // ─── Bollinger Bands ──────────────────────────────────────────────────────

    public record BollingerBands(double upper, double middle, double lower) {}

    public static BollingerBands bollingerBandsLast(double[] closes, int period, double stdDevMultiplier) {
        if (closes.length < period) return new BollingerBands(0, 0, 0);
        int from = closes.length - period;
        double sum = 0;
        for (int i = from; i < closes.length; i++) sum += closes[i];
        double sma = sum / period;

        double variance = 0;
        for (int i = from; i < closes.length; i++) {
            double diff = closes[i] - sma;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / period);

        return new BollingerBands(
            sma + stdDevMultiplier * stdDev,
            sma,
            sma - stdDevMultiplier * stdDev
        );
    }

    // ─── Supertrend ───────────────────────────────────────────────────────────

    public record SupertrendResult(double[] supertrend, boolean[] isBullish) {}

    /**
     * Supertrend indicator.
     * @param multiplier typically 3.0
     * @param period     ATR period, typically 10
     */
    public static SupertrendResult supertrend(
            double[] highs, double[] lows, double[] closes, int period, double multiplier) {
        int n = closes.length;
        double[] st = new double[n];
        boolean[] bull = new boolean[n];
        if (n < period + 1) return new SupertrendResult(st, bull);

        double[] atrArr = atr(highs, lows, closes, period);
        double[] upperBand = new double[n];
        double[] lowerBand = new double[n];

        for (int i = 0; i < n; i++) {
            double hl2 = (highs[i] + lows[i]) / 2.0;
            upperBand[i] = hl2 + multiplier * atrArr[i];
            lowerBand[i] = hl2 - multiplier * atrArr[i];
        }

        // Compute final supertrend with trend-following bands
        double[] finalUpper = new double[n];
        double[] finalLower = new double[n];
        System.arraycopy(upperBand, 0, finalUpper, 0, n);
        System.arraycopy(lowerBand, 0, finalLower, 0, n);

        bull[period] = closes[period] <= finalUpper[period];
        st[period] = bull[period] ? finalLower[period] : finalUpper[period];

        for (int i = period + 1; i < n; i++) {
            finalLower[i] = lowerBand[i] > finalLower[i - 1] || closes[i - 1] < finalLower[i - 1]
                ? lowerBand[i] : finalLower[i - 1];
            finalUpper[i] = upperBand[i] < finalUpper[i - 1] || closes[i - 1] > finalUpper[i - 1]
                ? upperBand[i] : finalUpper[i - 1];

            if (st[i - 1] == finalUpper[i - 1]) {
                bull[i] = closes[i] > finalUpper[i];
            } else {
                bull[i] = closes[i] >= finalLower[i];
            }
            st[i] = bull[i] ? finalLower[i] : finalUpper[i];
        }
        return new SupertrendResult(st, bull);
    }

    // ─── ADX (for regime detection) ───────────────────────────────────────────

    /** Returns ADX last value. ADX > 25 = trending, <= 25 = choppy/ranging */
    public static double adxLast(double[] highs, double[] lows, double[] closes, int period) {
        int n = closes.length;
        if (n < period * 2) return 0;
        double[] dmPlus = new double[n];
        double[] dmMinus = new double[n];
        double[] tr = new double[n];

        for (int i = 1; i < n; i++) {
            double upMove = highs[i] - highs[i - 1];
            double downMove = lows[i - 1] - lows[i];
            dmPlus[i] = (upMove > downMove && upMove > 0) ? upMove : 0;
            dmMinus[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
            tr[i] = Math.max(highs[i] - lows[i],
                Math.max(Math.abs(highs[i] - closes[i - 1]), Math.abs(lows[i] - closes[i - 1])));
        }

        // Smooth with Wilder's
        double trSmooth = 0, dpSmooth = 0, dnSmooth = 0;
        for (int i = 1; i <= period; i++) {
            trSmooth += tr[i];
            dpSmooth += dmPlus[i];
            dnSmooth += dmMinus[i];
        }

        double adx = 0;
        double prevAdx = 0;
        boolean first = true;
        for (int i = period + 1; i < n; i++) {
            trSmooth = trSmooth - trSmooth / period + tr[i];
            dpSmooth = dpSmooth - dpSmooth / period + dmPlus[i];
            dnSmooth = dnSmooth - dnSmooth / period + dmMinus[i];
            double diPlus = trSmooth == 0 ? 0 : 100 * dpSmooth / trSmooth;
            double diMinus = trSmooth == 0 ? 0 : 100 * dnSmooth / trSmooth;
            double dx = (diPlus + diMinus) == 0 ? 0 :
                100 * Math.abs(diPlus - diMinus) / (diPlus + diMinus);
            if (first) { prevAdx = dx; first = false; }
            else prevAdx = (prevAdx * (period - 1) + dx) / period;
        }
        return prevAdx;
    }

    // ─── Helper: extract double arrays from BigDecimal lists ─────────────────

    public static double[] toDoubleArray(List<BigDecimal> values) {
        return values.stream().mapToDouble(BigDecimal::doubleValue).toArray();
    }

    public static long[] toLongArray(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).toArray();
    }
}
