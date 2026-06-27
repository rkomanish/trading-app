package com.niftyautotrader.service.replay;

import com.niftyautotrader.model.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates 1-minute candles into higher timeframes (3m, 5m, 15m, 30m, 60m).
 *
 * The replay module stores only 1-minute base candles per session and builds every
 * other timeframe on the fly — this keeps storage minimal and guarantees the
 * higher-timeframe bars are always consistent with the 1m data the user sees.
 *
 * Buckets are aligned to the clock (e.g. 5m bars start at :00, :05, :10 …) by
 * flooring each candle's epoch-second to the timeframe boundary. This matches how
 * NSE charting tools group intraday candles.
 */
public final class CandleAggregator {

    private CandleAggregator() {}

    /** Minutes per supported timeframe. */
    public static int minutesOf(String timeframe) {
        return switch (timeframe) {
            case "1m"  -> 1;
            case "3m"  -> 3;
            case "5m"  -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "60m" -> 60;
            default    -> throw new IllegalArgumentException("Unsupported timeframe: " + timeframe);
        };
    }

    /**
     * Aggregate a sorted list of 1-minute candles into the requested timeframe.
     * If the timeframe is "1m" the input is returned unchanged.
     */
    public static List<Candle> aggregate(List<Candle> oneMinute, String timeframe) {
        int minutes = minutesOf(timeframe);
        if (minutes == 1) return oneMinute;
        if (oneMinute.isEmpty()) return List.of();

        long bucketSeconds = minutes * 60L;
        Map<Long, List<Candle>> buckets = new LinkedHashMap<>();
        for (Candle c : oneMinute) {
            long epoch = c.getOpenTime().toEpochSecond();
            long bucket = epoch - Math.floorMod(epoch, bucketSeconds);
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(c);
        }

        List<Candle> result = new ArrayList<>(buckets.size());
        for (List<Candle> group : buckets.values()) {
            result.add(merge(group, timeframe));
        }
        return result;
    }

    private static Candle merge(List<Candle> group, String timeframe) {
        Candle first = group.get(0);
        Candle last  = group.get(group.size() - 1);

        BigDecimal high = first.getHigh();
        BigDecimal low  = first.getLow();
        long volume = 0;
        for (Candle c : group) {
            if (c.getHigh().compareTo(high) > 0) high = c.getHigh();
            if (c.getLow().compareTo(low) < 0)   low  = c.getLow();
            volume += c.getVolume();
        }

        Candle agg = new Candle();
        agg.setSymbol(first.getSymbol());
        agg.setTimeframe(timeframe);
        agg.setOpenTime(first.getOpenTime());
        agg.setOpen(first.getOpen());
        agg.setHigh(high);
        agg.setLow(low);
        agg.setClose(last.getClose());
        agg.setVolume(volume);
        return agg;
    }
}
