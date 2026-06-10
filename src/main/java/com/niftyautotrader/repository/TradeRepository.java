package com.niftyautotrader.repository;

import com.niftyautotrader.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findByExitTimeIsNullOrderByEntryTimeDesc();
    List<Trade> findByEntryTimeBetweenOrderByEntryTimeDesc(ZonedDateTime from, ZonedDateTime to);
    List<Trade> findTop50ByOrderByEntryTimeDesc();

    @Query("SELECT COALESCE(SUM(t.realizedPnl), 0) FROM Trade t WHERE t.entryTime >= :from AND t.entryTime <= :to")
    BigDecimal sumRealizedPnlBetween(ZonedDateTime from, ZonedDateTime to);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.entryTime >= :from")
    long countTradesSince(ZonedDateTime from);
}
