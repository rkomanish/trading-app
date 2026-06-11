package com.niftyautotrader.repository;

import com.niftyautotrader.model.Candle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;

public interface CandleRepository extends JpaRepository<Candle, Long> {
    List<Candle> findBySymbolAndTimeframeAndOpenTimeAfterOrderByOpenTimeAsc(
        String symbol, String timeframe, ZonedDateTime after);

    List<Candle> findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(
        String symbol, String timeframe, ZonedDateTime from, ZonedDateTime to);

    List<Candle> findTop100BySymbolAndTimeframeOrderByOpenTimeDesc(String symbol, String timeframe);

    boolean existsBySymbolAndTimeframeAndOpenTime(String symbol, String timeframe, ZonedDateTime openTime);
}
