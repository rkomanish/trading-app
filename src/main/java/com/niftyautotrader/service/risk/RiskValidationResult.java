package com.niftyautotrader.service.risk;

import java.util.Collections;
import java.util.List;

public final class RiskValidationResult {

    private final boolean approved;
    private final List<RiskViolation> violations;

    private RiskValidationResult(boolean approved, List<RiskViolation> violations) {
        this.approved = approved;
        this.violations = List.copyOf(violations);
    }

    public static RiskValidationResult approved() {
        return new RiskValidationResult(true, Collections.emptyList());
    }

    public static RiskValidationResult rejected(List<RiskViolation> violations) {
        return new RiskValidationResult(false, violations);
    }

    public static RiskValidationResult rejected(RiskViolation violation) {
        return new RiskValidationResult(false, List.of(violation));
    }

    public boolean isApproved() { return approved; }
    public boolean isRejected() { return !approved; }
    public List<RiskViolation> getViolations() { return violations; }

    public String firstViolationMessage() {
        return violations.isEmpty() ? "" : violations.get(0).message();
    }
}
