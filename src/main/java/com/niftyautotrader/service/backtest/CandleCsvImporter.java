package com.niftyautotrader.service.backtest;

import com.niftyautotrader.model.Candle;
import com.niftyautotrader.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports historical OHLCV candles from CSV for backtesting.
 *
 * Expected CSV format (header required, comma-separated):
 *   timestamp,open,high,low,close,volume
 *
 * Supported timestamp formats:
 *   2024-01-15 09:15:00          (assumed IST)
 *   2024-01-15T09:15:00          (assumed IST)
 *   2024-01-15T09:15:00+05:30    (explicit offset)
 *
 * Rows that fail to parse are skipped and counted, never abort the import.
 * Duplicate candles (same symbol+timeframe+openTime) are skipped via the DB
 * unique constraint.
 */
@Service
public class CandleCsvImporter {

    private static final Logger log = LoggerFactory.getLogger(CandleCsvImporter.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final DateTimeFormatter[] LOCAL_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    };

    private final CandleRepository candleRepo;

    public CandleCsvImporter(CandleRepository candleRepo) {
        this.candleRepo = candleRepo;
    }

    public record ImportResult(int imported, int skipped, int duplicates, List<String> errors) {}

    public ImportResult importCsv(MultipartFile file, String symbol, String timeframe) {
        int imported = 0, skipped = 0, duplicates = 0;
        List<String> errors = new ArrayList<>();
        List<Candle> batch = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String header = reader.readLine();
            if (header == null) {
                return new ImportResult(0, 0, 0, List.of("Empty file"));
            }

            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                try {
                    Candle candle = parseLine(line, symbol, timeframe);
                    batch.add(candle);
                    if (batch.size() >= 500) {
                        int[] result = saveBatch(batch);
                        imported += result[0];
                        duplicates += result[1];
                        batch.clear();
                    }
                } catch (Exception e) {
                    skipped++;
                    if (errors.size() < 10) {
                        errors.add("Line " + lineNo + ": " + e.getMessage());
                    }
                }
            }
            if (!batch.isEmpty()) {
                int[] result = saveBatch(batch);
                imported += result[0];
                duplicates += result[1];
            }
        } catch (Exception e) {
            errors.add("Fatal: " + e.getMessage());
        }

        log.info("CSV import complete: symbol={} timeframe={} imported={} skipped={} duplicates={}",
            symbol, timeframe, imported, skipped, duplicates);
        return new ImportResult(imported, skipped, duplicates, errors);
    }

    private Candle parseLine(String line, String symbol, String timeframe) {
        String[] parts = line.split(",");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Expected at least 5 columns, got " + parts.length);
        }

        Candle c = new Candle();
        c.setSymbol(symbol);
        c.setTimeframe(timeframe);
        c.setOpenTime(parseTimestamp(parts[0].trim()));
        c.setOpen(new BigDecimal(parts[1].trim()));
        c.setHigh(new BigDecimal(parts[2].trim()));
        c.setLow(new BigDecimal(parts[3].trim()));
        c.setClose(new BigDecimal(parts[4].trim()));
        c.setVolume(parts.length > 5 && !parts[5].trim().isEmpty()
            ? Long.parseLong(parts[5].trim()) : 0L);
        return c;
    }

    private ZonedDateTime parseTimestamp(String raw) {
        // Try explicit offset first (e.g. 2024-01-15T09:15:00+05:30)
        try {
            return ZonedDateTime.parse(raw).withZoneSameInstant(IST);
        } catch (Exception ignored) { }

        for (DateTimeFormatter fmt : LOCAL_FORMATS) {
            try {
                return LocalDateTime.parse(raw, fmt).atZone(IST);
            } catch (Exception ignored) { }
        }
        throw new IllegalArgumentException("Unparseable timestamp: " + raw);
    }

    private int[] saveBatch(List<Candle> batch) {
        int saved = 0, dup = 0;
        for (Candle c : batch) {
            if (candleRepo.existsBySymbolAndTimeframeAndOpenTime(
                    c.getSymbol(), c.getTimeframe(), c.getOpenTime())) {
                dup++;
            } else {
                candleRepo.save(c);
                saved++;
            }
        }
        return new int[]{saved, dup};
    }
}
