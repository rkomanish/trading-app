package com.niftyautotrader.repository;

import com.niftyautotrader.model.DailyPnl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyPnlRepository extends JpaRepository<DailyPnl, Long> {
    Optional<DailyPnl> findByTradingDate(LocalDate date);
    List<DailyPnl> findByTradingDateBetweenOrderByTradingDateDesc(LocalDate from, LocalDate to);
    List<DailyPnl> findTop10ByOrderByTradingDateDesc();
}
