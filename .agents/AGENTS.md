# Antigravity Engineering Rules & Operating Principles for PocketStream

This document defines the strict, non-negotiable engineering rules and principles for all AI agents, engineers, and contributors working in the PocketStream repository.

---

## 1. Core Operating Principles

### Rule 1: Existing Functionality Must Be Preserved
Every change must preserve existing verified functionality. Never break direct play, hardware transcoding, pairing flows, TV grid layout, or remote synchronization while introducing features or fixes.

### Rule 2: Never Silently Change Approved Requirements
Canonical requirements documented in `docs/REQUIREMENTS.md` and decisions in `docs/DECISION_LOG.md` are the single source of truth. Never alter requirements, behaviors, UI texts, or defaults without explicit user approval.

### Rule 3: Never Invent APIs, Protocols, or Functionality
Do not improvise or introduce undocumented network endpoints, JSON fields, URL schemes, or protocol variants. All network interactions must strictly conform to `docs/PROTOCOL_SPEC.md`.

### Rule 4: Never Assume Platform Implementations Are Identical
Android Phone (`apps/mobile`), Android TV (`apps/tv`), and iOS (`ios/PocketStream`) have distinct architectures, SDKs, and platform behaviors. Always verify actual source code before assuming parity or reusing paradigms across platforms.

### Rule 5: Inspect Existing Code Before Proposing Changes
Always inspect the relevant source code, data flow, and dependencies before proposing or implementing changes. Do not make assumptions based on file paths or class names alone.

### Rule 6: Ask Questions When Requirements Are Ambiguous
If a feature request, edge case, or bug report is ambiguous or conflicts with existing behavior, ask the user for clarification before writing code. Do not convert assumptions into requirements.

### Rule 7: Obtain Approval for Breaking or Architectural Changes
Any change affecting communication protocols (ports 8080, 8085, 8088), storage boundaries, concurrency models, dependency upgrades, or breaking API changes requires prior user review and approval.

### Rule 8: Avoid Unnecessary Dependencies and Refactoring
Keep the dependency footprint minimal. Never add a third-party library if the task can be achieved cleanly with platform-native APIs (e.g. Android Jetpack, Apple Network.framework / AVFoundation). Do not perform unrelated stylistic refactoring.

### Rule 9: Never Remove Existing Tests to Make Builds Pass
Tests exist to safeguard system integrity. If a test fails, identify and fix the underlying defect. Never delete, comment out, or disable tests to force a build to pass.

### Rule 10: Update Canonical Documentation After Approved Changes
Whenever an approved change modifies functionality, endpoints, or UI architecture, update the canonical documents under `docs/` (`REQUIREMENTS.md`, `ARCHITECTURE.md`, `PROTOCOL_SPEC.md`, `DECISION_LOG.md`) to maintain the single source of truth.
