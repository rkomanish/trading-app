package com.niftyautotrader.repository;

import com.niftyautotrader.model.SentimentSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SentimentSnapshotRepository extends JpaRepository<SentimentSnapshot, Long> {
    Optional<SentimentSnapshot> findTopByOrderByCapturedAtDesc();
    java.util.List<SentimentSnapshot> findTop20ByOrderByCapturedAtDesc();
}
