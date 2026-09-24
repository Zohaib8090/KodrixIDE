---
phase: 1
slug: the-shell-foundation
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-25
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 / Android Instrumentation / grep |
| **Config file** | app/build.gradle.kts |
| **Quick run command** | `./gradlew testDebugUnitTest` |
| **Full suite command** | `./gradlew connectedDebugAndroidTest` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run grep/file existence checks
- **After every plan wave:** Run `./gradlew testDebugUnitTest`
- **Before `/gsd-verify-work`:** App must build and launch correctly
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 1-01-01 | 01 | 1 | Foundation | existence | `ls app/src/main/cpp/native-lib.cpp` | ❌ W0 | ⬜ pending |
| 1-01-02 | 01 | 1 | PTY Bridge | unit | `./gradlew testDebugUnitTest` | ✅ | ⬜ pending |
| 1-02-01 | 02 | 2 | UI Component | existence | `grep "TerminalComponent" MainActivity.kt` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/main/cpp/CMakeLists.txt` — NDK build configuration
- [ ] `app/src/main/cpp/native-lib.cpp` — JNI entry point stub
- [ ] `app/src/test/kotlin/com/zohaib/codeossandriod/PtyBridgeTest.kt` — Test for native bridge

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Terminal responsiveness | Integrated Terminal | Requires user interaction | Launch app, type 'ls', verify output is shown. |
| PTY Resizing | PTY support | Difficult to automate UI resize | Rotate device and verify terminal columns update. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
