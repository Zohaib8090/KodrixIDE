# Phase 01: The Shell Foundation - Context

**Gathered:** 2026-04-25
**Status:** Ready for planning
**Source:** Initial project discussion

<domain>
## Phase Boundary
This phase focuses on establishing the native Android foundation and the integrated terminal shell. It concludes when a user can open the app and see a functional terminal prompt that responds to basic commands like `ls` and `pwd`.

</domain>

<decisions>
## Implementation Decisions

### Android Foundation
- **Package Name**: `com.zohaib.codeossandriod`
- **Minimum SDK**: API 29 (Android 10)
- **UI Framework**: Jetpack Compose (Material 3)

### Terminal Engine
- **Approach**: JNI-based PTY bridge using `libvterm`.
- **UI Component**: Custom Compose Terminal using Canvas for performance.
- **PTY Bridge**: Use `ProcessBuilder` or native `forkpty` via JNI to talk to `/system/bin/sh`.

### the agent's Discretion
- Selection of the exact JNI library for PTY if `libvterm` is too heavy.
- Buffer management strategy for the terminal (e.g., limit to 1000 lines).

</decisions>

<canonical_refs>
## Canonical References
- `.planning/PROJECT.md`
- `.planning/REQUIREMENTS.md`
- `.planning/ROADMAP.md`

</canonical_refs>

<specifics>
## Specific Ideas
- Terminal should show green prompt `zohaib@android:~$` for consistency with initial mock.
- Use `Monospace` font for the terminal output.

</specifics>

<deferred>
## Deferred Ideas
- Phase 2: Bundling Node.js and Git.
- Phase 3: High-performance code editor.

</deferred>

---
*Phase: 01-the-shell-foundation*
*Context gathered: 2026-04-25 after discussion*
