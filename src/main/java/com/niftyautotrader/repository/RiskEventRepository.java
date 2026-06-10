package com.niftyautotrader.repository;

import com.niftyautotrader.model.RiskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;

public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {
    List<RiskEvent> findByOccurredAtAfterOrderByOccurredAtDesc(ZonedDateTime after);
    List<RiskEvent> findTop20ByOrderByOccurredAtDesc();
}
