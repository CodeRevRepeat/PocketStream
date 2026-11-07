---
name: pocketstream-engineering
description: Engineering guideline and workflow for PocketStream feature implementation, bug fixes, and architectural modifications.
---

# PocketStream Engineering Skill

Use this skill when implementing new features, fixing defects, enhancing UI/UX, or modifying system architecture in the PocketStream multi-platform codebase.

## 1. Operating Rules & Pre-Flight Checklist

Before proposing or modifying any code in PocketStream, you MUST execute the following sequence:

1. **Consult the Canonical Source of Truth**:
   - Check `docs/REQUIREMENTS.md` to confirm requirement status (Confirmed, Approved, Proposed, Unknown).
   - Review `docs/PROTOCOL_SPEC.md` if the change touches HTTP endpoints (8080, 8085), UDP broadcasts (8088), or mDNS (5353).
   - Review `docs/DECISION_LOG.md` to understand why current patterns were chosen (e.g. Direct Play preference, 7% thumbnail frame, 4-column grid).

2. **Inspect Actual Code**:
   - Trace the end-to-end data flow in actual source files:
     - Android Mobile: `apps/mobile/src/main/java/com/pocketstream/mobile/`
     - Android TV: `apps/tv/src/main/java/com/pocketstream/tv/`
     - iOS: `ios/PocketStream/`
     - Shared Protocol: `shared/protocol/src/main/java/com/pocketstream/protocol/`

3. **Assess Cross-Platform Parity & Impact**:
   - If a change is made to the mobile streaming server (`PocketHttpServer.kt`), check if `PocketHttpServer.swift` requires matching adjustments.
   - If a protocol message in `shared/protocol` is modified, verify whether the iOS client models in `ios/PocketStream/Models/` must be updated.

4. **Preserve Approved User Decisions**:
   - Direct Play MUST remain preferred in all compatibility dialogs.
   - 10-bit analysis MUST remain pure technical track analysis (no filename parsing).
   - TV grid layout MUST maintain 4 columns without screen overflow.
   - TV connection MUST remain 1:1 exclusive and reject competing devices with HTTP 409 Conflict.

5. **Post-Implementation Documentation**:
   - Update `docs/REQUIREMENTS.md`, `docs/DECISION_LOG.md`, and `docs/FUNCTIONALITY_MATRIX.md` when approved changes land.
