package com.niftyautotrader.service.backtest;

import java.math.BigDecimal;
import java.util.List;

/** Utility called from Thymeleaf to generate SVG polyline points for equity curve sparklines. */
public class SparklineHelper {

    private SparklineHelper() {}

    public static String buildPoints(List<BigDecimal> curve, int width, int height) {
        if (curve == null || curve.size() < 2) return "";

        BigDecimal minV = curve.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal maxV = curve.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal range = maxV.subtract(minV);
        if (range.compareTo(BigDecimal.ZERO) == 0) range = BigDecimal.ONE;

        int n = curve.size();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double x = (double) i / (n - 1) * width;
            double yNorm = curve.get(i).subtract(minV).doubleValue() / range.doubleValue();
            double y = height - yNorm * (height - 4) - 2; // 2px padding top/bottom
            if (i > 0) sb.append(' ');
            sb.append(String.format("%.1f,%.1f", x, y));
        }
        return sb.toString();
    }
}
