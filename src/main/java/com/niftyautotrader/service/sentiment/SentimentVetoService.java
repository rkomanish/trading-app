package com.niftyautotrader.service.sentiment;

import com.niftyautotrader.config.SentimentProperties;
import com.niftyautotrader.model.SentimentSnapshot;
import com.niftyautotrader.model.SignalDirection;
import com.niftyautotrader.repository.SentimentSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Advisory-only: the LLM may say "no" to a trade. It may NEVER originate or size one.
 *
 * Veto rules (config-driven):
 *   - BEARISH with confidence >= threshold → veto LONG_CE entries
 *   - BULLISH with confidence >= threshold → veto LONG_PE entries
 *   - NEUTRAL or low-confidence → pass through
 *   - On LLM failure (fallback=true) → NEUTRAL → no veto
 */
@Service
public class SentimentVetoService {

    private static final Logger log = LoggerFactory.getLogger(SentimentVetoService.class);

    private final SentimentSnapshotRepository snapshotRepo;
    private final SentimentProperties sentimentProps;

    public SentimentVetoService(SentimentSnapshotRepository snapshotRepo,
                                 SentimentProperties sentimentProps) {
        this.snapshotRepo = snapshotRepo;
        this.sentimentProps = sentimentProps;
    }

    /**
     * @return null if trade is allowed, non-null veto reason string if blocked
     */
    public String shouldVeto(SignalDirection direction) {
        Optional<SentimentSnapshot> latest = snapshotRepo.findTopByOrderByCapturedAtDesc();
        if (latest.isEmpty()) return null; // no snapshot yet — allow

        SentimentSnapshot snap = latest.get();
        if (snap.isFallback()) return null; // LLM failure — default to NEUTRAL, never block

        double threshold = sentimentProps.getVetoConfidenceThreshold();
        SentimentSnapshot.Sentiment sentiment = snap.getSentiment();
        double confidence = snap.getConfidence();

        if (direction == SignalDirection.LONG_CE
                && sentiment == SentimentSnapshot.Sentiment.BEARISH
                && confidence >= threshold) {
            String reason = String.format(
                "Sentiment BEARISH (conf=%.2f >= %.2f) — LONG_CE vetoed. Risks: %s",
                confidence, threshold, snap.getKeyRisks());
            log.info("Sentiment veto: {}", reason);
            return reason;
        }

        if (direction == SignalDirection.LONG_PE
                && sentiment == SentimentSnapshot.Sentiment.BULLISH
                && confidence >= threshold) {
            String reason = String.format(
                "Sentiment BULLISH (conf=%.2f >= %.2f) — LONG_PE vetoed. Risks: %s",
                confidence, threshold, snap.getKeyRisks());
            log.info("Sentiment veto: {}", reason);
            return reason;
        }

        return null;
    }
}
