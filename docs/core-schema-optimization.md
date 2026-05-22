# Core Schema Optimization

> **CCE Compliance Service** — Schema cleanup for fresh deployment  
> **Status**: Proposed | **Target**: v1.2.0  
> **Last Updated**: 2025-05-28  
> **Deployment Model**: Fresh deployment (no existing data to migrate)

---

## 1. Overview

This document covers core schema cleanup decisions for the Compliance Service's fresh deployment. These are **permanent structural improvements** — not temporary insights optimizations (see [insights-optimization.md](insights-optimization.md) for those).

---

## 2. Table Naming: `compliance_event_log`

**Rationale:** The original table name `event_log` is generic and ambiguous in a shared database with tables from multiple CCE services (`inbound_event` from Collector, `intelligence_event_log` from Compliance/Intelligence). The fresh deployment uses `compliance_event_log` to clearly indicate Compliance Service ownership, aligning with `intelligence_event_log` naming conventions.

**Code changes:**

- `EventLog.java`: `@Table(name = "compliance_event_log")`

---

## 3. Dead Column Removal

**Problem:** Three columns were present in the original schema but never populated anywhere in the codebase:

| Table | Column | Type | Finding |
|-------|--------|------|--------|
| `compliance_event_log` | `matched_step_instance_id` | `UUID` | Never set — `setMatchedStepInstanceId()` has zero call sites |
| `audit_log` | `ip_address` | `VARCHAR(45)` | Never set — `setIpAddress()` has zero call sites |
| `intelligence_event_log` | `error_message` | `TEXT` | Never set — `setErrorMessage()` has zero call sites |

**Solution (fresh deploy):** These columns are **not included** in the initial DDL. The corresponding fields, getters, and setters are removed from the JPA entities.

**Impact:** No application behavior changes. These columns were never read or written by any business logic.

---

## 4. Flyway Migration

These changes are incorporated into the initial schema migration:

| Migration | Change |
|-----------|--------|
| `V1__initial_schema.sql` | Table named `compliance_event_log` (not `event_log`); `matched_step_instance_id` excluded; `audit_log.ip_address` excluded; `intelligence_event_log.error_message` excluded |

---

## 5. Summary

| Change | Type | Affected Entity | Rationale |
|--------|------|-----------------|-----------|
| Table named `compliance_event_log` (not `event_log`) | Naming | `EventLog` | Disambiguate in shared DB |
| Omit `matched_step_instance_id` from `compliance_event_log` | Not created | `EventLog` | Zero call sites — dead code |
| Omit `ip_address` from `audit_log` | Not created | `AuditLog` | Zero call sites — dead code |
| Omit `error_message` from `intelligence_event_log` | Not created | `IntelligenceEventLog` | Zero call sites — dead code |
