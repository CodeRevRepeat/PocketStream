---
name: pocketstream-security-audit
description: Security, performance, reliability, dependency and code-quality audit guide for the PocketStream ecosystem.
---

# PocketStream Security & Quality Audit Skill

Use this skill when performing security reviews, vulnerability assessments, performance evaluations, and code quality audits across PocketStream (Android Mobile, Android TV, iOS, and Shared Protocol).

## 1. Core Audit Directives

1. **Strictly Read-Only During Audits**:
   - Never modify application source code, configuration files, or dependencies during an audit.
   - Do not delete files or upgrade libraries.
   - All proposed remediations must be documented in reports for stakeholder approval before execution.

2. **Evidence-Based Findings**:
   - Do not claim a vulnerability or bug exists without concrete source code evidence.
   - For every finding, cite the exact file path, line numbers, code snippets, and reproduction conditions.
   - Clearly state whether a finding is **Verified** (confirmed directly in code) or **Requires Investigation** (theoretical risk depending on runtime environments).

3. **Required Finding Schema**:
   Each finding in an audit report must contain:
   - **Finding ID**: e.g., `SEC-001`, `PERF-001`, `REL-001`.
   - **Severity**: `Critical`, `High`, `Medium`, `Low`, or `Informational`.
   - **Affected Application**: Android Mobile, Android TV, iOS, or Shared Protocol.
   - **Affected File & Location**: Full file path and line numbers.
   - **Technical Explanation**: Clear explanation of the underlying mechanism.
   - **Evidence**: Verifiable code snippet from the repository.
   - **Potential Impact**: Security or operational impact if exploited or triggered.
   - **Recommended Remediation**: Specific, actionable steps to resolve the issue.
   - **Verification Status**: `Verified` or `Requires Investigation`.

4. **16 Mandatory Audit Dimensions**:
   - Authentication and authorization
   - Device pairing and bonding
   - Network security & cleartext transit
   - TLS and certificate validation
   - File-system access and sandboxing
   - Path traversal and input validation
   - Secrets and sensitive data
   - Memory and resource management (file descriptor leaks, buffer sizing)
   - Concurrency and race conditions (thread locks, synchronized blocks)
   - Streaming performance and latency
   - Battery and CPU usage (hardware vs software encoding)
   - Dependency vulnerabilities and known CVEs
   - Error handling and crash resilience
   - Connection lifecycle and recovery (network switches, reconnects)
   - Cross-platform compatibility and behavioral parity
   - Automated test coverage gaps
