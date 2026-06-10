package com.niftyautotrader.service.marketdata;

import com.niftyautotrader.broker.Broker;
import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import com.niftyautotrader.service.paper.PaperBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Aggregates market data ticks into OHLCV candles and stores them.
 * In paper mode, accepts price feeds from tests or manual injection.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CandleRepository candleRepo;
    private final Broker broker;

    public MarketDataService(CandleRepository candleRepo, Broker broker) {
        this.candleRepo = candleRepo;
        this.broker = broker;
    }

    /**
     * Ingest a completed candle (called by Kite WS adapter or test harness).
     */
    public Candle ingestCandle(String symbol, String timeframe,
                                ZonedDateTime openTime, BigDecimal open, BigDecimal high,
                                BigDecimal low, BigDecimal close, long volume) {
        Candle candle = new Candle();
        candle.setSymbol(symbol);
        candle.setTimeframe(timeframe);
        candle.setOpenTime(openTime);
        candle.setOpen(open);
        candle.setHigh(high);
        candle.setLow(low);
        candle.setClose(close);
        candle.setVolume(volume);

        // Update broker price feed (paper mode)
        if (broker instanceof PaperBroker pb) {
            pb.updatePrice(symbol, close);
        }

        return candleRepo.save(candle);
    }

    public List<Candle> getRecentCandles(String symbol, String timeframe, int count) {
        return candleRepo.findTop100BySymbolAndTimeframeOrderByOpenTimeDesc(symbol, timeframe)
            .stream().limit(count).toList();
    }
}
