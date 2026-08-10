package org.openphc.cce.compliance.domain.enums;

public enum StepState {
    PENDING,
    DUE,
    /**
     * Legacy state — no longer part of the lifecycle. A DUE step now waits for its missedDate
     * and goes straight to MISSED (or SKIPPED); nothing transitions a step into OVERDUE any more.
     *
     * <p>Retained so rows written before the change still map when loaded. Migration
     * {@code V8__reconcile_legacy_overdue_steps.sql} reconciles the ones adopted environments
     * already hold back to DUE; the constant covers any that arrive after it runs — an old
     * Compliance instance still consuming DUE_TO_OVERDUE during a rolling deploy.
     */
    OVERDUE,
    MISSED,
    COMPLETED,
    SKIPPED
}
