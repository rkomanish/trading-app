package com.niftyautotrader.service.indicators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("IndicatorUtils — unit tests")
class IndicatorUtilsTest {

    // ─── EMA ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EMA(3) on simple series matches manual calculation")
    void ema3Simple() {
        double[] closes = {10, 11, 12, 13, 14};
        double[] ema = IndicatorUtils.ema(closes, 3);
        // Seed: SMA(3) of first 3 = (10+11+12)/3 = 11.0
        assertThat(ema[2]).isCloseTo(11.0, within(0.01));
        // Next: 13 * (2/4) + 11.0 * (2/4) = 6.5 + 5.5 = 12.0
        assertThat(ema[3]).isCloseTo(12.0, within(0.01));
        // Next: 14 * 0.5 + 12.0 * 0.5 = 13.0
        assertThat(ema[4]).isCloseTo(13.0, within(0.01));
    }

    @Test
    @DisplayName("EMA returns zero-filled array when length < period")
    void emaTooShort() {
        double[] closes = {10, 11};
        double[] ema = IndicatorUtils.ema(closes, 5);
        assertThat(ema).containsOnly(0.0);
    }

    @Test
    @DisplayName("emaLast returns final value")
    void emaLast() {
        double[] closes = {100, 101, 102, 103, 104, 105};
        double last = IndicatorUtils.emaLast(closes, 3);
        assertThat(last).isGreaterThan(102.0).isLessThan(106.0);
    }

    // ─── RSI ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RSI of flat series is 50")
    void rsiFlatSeries() {
        double[] closes = new double[20];
        for (int i = 0; i < 20; i++) closes[i] = 100 + (i % 2 == 0 ? 1 : -1);
        double rsi = IndicatorUtils.rsiLast(closes, 14);
        assertThat(rsi).isBetween(45.0, 55.0);
    }

    @Test
    @DisplayName("RSI of strong uptrend approaches 100")
    void rsiStrongUp() {
        double[] closes = new double[20];
        for (int i = 0; i < 20; i++) closes[i] = 100 + i;
        double rsi = IndicatorUtils.rsiLast(closes, 14);
        assertThat(rsi).isGreaterThan(90.0);
    }

    @Test
    @DisplayName("RSI of strong downtrend approaches 0")
    void rsiStrongDown() {
        double[] closes = new double[20];
        for (int i = 0; i < 20; i++) closes[i] = 100 - i * 2;
        double rsi = IndicatorUtils.rsiLast(closes, 14);
        assertThat(rsi).isLessThan(15.0);
    }

    @Test
    @DisplayName("RSI is bounded 0..100")
    void rsiBounded() {
        double[] closes = {10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 1, 1, 1, 1, 1, 1};
        double rsi = IndicatorUtils.rsiLast(closes, 14);
        assertThat(rsi).isBetween(0.0, 100.0);
    }

    // ─── ATR ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ATR is positive for volatile data")
    void atrPositive() {
        double[] highs = {105, 106, 104, 108, 107, 110, 108, 111, 109, 112, 110, 113, 111, 114};
        double[] lows  = { 99, 100,  98, 102, 101, 104, 102, 105, 103, 106, 104, 107, 105, 108};
        double[] cls   = {102, 103, 101, 105, 104, 107, 105, 108, 106, 109, 107, 110, 108, 111};
        double atr = IndicatorUtils.atrLast(highs, lows, cls, 14);
        assertThat(atr).isGreaterThan(0);
    }

    @Test
    @DisplayName("ATR of flat candles is near zero")
    void atrFlat() {
        double[] highs = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        double[] lows  = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        double[] cls   = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        double atr = IndicatorUtils.atrLast(highs, lows, cls, 14);
        assertThat(atr).isCloseTo(0.0, within(0.001));
    }

    // ─── VWAP ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VWAP with equal prices equals that price")
    void vwapEqualPrices() {
        double[] h = {100, 100, 100};
        double[] l = {100, 100, 100};
        double[] c = {100, 100, 100};
        long[] v = {1000, 2000, 1500};
        double vwap = IndicatorUtils.vwapLast(h, l, c, v);
        assertThat(vwap).isCloseTo(100.0, within(0.01));
    }

    @Test
    @DisplayName("VWAP is volume-weighted")
    void vwapVolumeWeighted() {
        double[] h = {110, 90};
        double[] l = {110, 90};
        double[] c = {110, 90}; // TP=110 and 90
        long[] v   = {1000, 1000};
        double vwap = IndicatorUtils.vwapLast(h, l, c, v);
        assertThat(vwap).isCloseTo(100.0, within(0.01));
    }

    // ─── Bollinger Bands ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Bollinger middle = SMA, upper > middle, lower < middle")
    void bollingerOrdering() {
        double[] closes = new double[20];
        for (int i = 0; i < 20; i++) closes[i] = 100 + Math.sin(i);
        var bb = IndicatorUtils.bollingerBandsLast(closes, 20, 2.0);
        assertThat(bb.upper()).isGreaterThan(bb.middle());
        assertThat(bb.lower()).isLessThan(bb.middle());
    }

    @Test
    @DisplayName("Bollinger bands collapse on flat series")
    void bollingerFlat() {
        double[] closes = new double[20];
        java.util.Arrays.fill(closes, 100.0);
        var bb = IndicatorUtils.bollingerBandsLast(closes, 20, 2.0);
        assertThat(bb.upper()).isCloseTo(100.0, within(0.001));
        assertThat(bb.lower()).isCloseTo(100.0, within(0.001));
    }

    // ─── Supertrend ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Supertrend switches from bearish to bullish on sustained up-move")
    void supertrendTrend() {
        int n = 30;
        double[] highs = new double[n];
        double[] lows  = new double[n];
        double[] cls   = new double[n];

        // First half: declining
        for (int i = 0; i < 15; i++) {
            cls[i]   = 100 - i;
            highs[i] = cls[i] + 2;
            lows[i]  = cls[i] - 2;
        }
        // Second half: rallying strongly
        for (int i = 15; i < n; i++) {
            cls[i]   = 85 + (i - 15) * 3;
            highs[i] = cls[i] + 2;
            lows[i]  = cls[i] - 2;
        }

        var result = IndicatorUtils.supertrend(highs, lows, cls, 10, 3.0);
        // Last bar should be bullish after the strong rally
        assertThat(result.isBullish()[n - 1]).isTrue();
    }

    // ─── ADX ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADX > 25 for strong trend")
    void adxStrongTrend() {
        int n = 40;
        double[] highs = new double[n];
        double[] lows  = new double[n];
        double[] cls   = new double[n];
        for (int i = 0; i < n; i++) {
            cls[i] = 100 + i * 2;
            highs[i] = cls[i] + 1;
            lows[i] = cls[i] - 1;
        }
        double adx = IndicatorUtils.adxLast(highs, lows, cls, 14);
        assertThat(adx).isGreaterThan(20.0);
    }
}
