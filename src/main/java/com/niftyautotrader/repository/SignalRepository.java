package com.niftyautotrader.repository;

import com.niftyautotrader.model.Signal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;

public interface SignalRepository extends JpaRepository<Signal, Long> {
    List<Signal> findByGeneratedAtAfterOrderByGeneratedAtDesc(ZonedDateTime after);
    List<Signal> findTop50ByOrderByGeneratedAtDesc();
}
