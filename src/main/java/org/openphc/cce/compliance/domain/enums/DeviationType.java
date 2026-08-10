package org.openphc.cce.compliance.domain.enums;

public enum DeviationType {
    /**
     * Legacy type — no longer raised. It marked the DUE → OVERDUE transition, which has been
     * removed from the lifecycle (see {@link StepState#OVERDUE}); a step past its tolerance
     * window now raises nothing until missedDate, where it raises {@link #MISSED}. Retained
     * read-only so the deviations adopted environments already hold still map when loaded.
     */
    OVERDUE,
    MISSED,
    ORDER_VIOLATION
}
