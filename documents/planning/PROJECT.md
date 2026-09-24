# CodeOSS Android

A high-performance, purely native IDE for Android that provides a "VS Code-like" experience using Jetpack Compose and Skia.

## Context
Traditional "VS Code on Android" solutions rely on WebViews and local web servers (code-server). This project aims to break away from the browser, offering a 100% local, native Android application with a high-performance editor, integrated shell, and full extension support via a bundled Node.js runtime.

## Core Value
* **Performance**: Sub-16ms frame times even with large files.
* **Native Experience**: Deep integration with Android's system, file picker, and multitasking.
* **Extensibility**: Support for Language Server Protocol (LSP) and existing VS Code extensions.
* **Complete Dev Environment**: Bundled Node.js, Git, and a full terminal shell.

## Architecture
* **Frontend**: Kotlin + Jetpack Compose (using Canvas for Editor rendering).
* **Editor**: High-performance custom text engine (inspired by Sora Editor).
* **Backend Runtime**: Bundled Node.js (ARM64) for Extension Host and LSPs.
* **Version Control**: Bundled Native Git binary.
* **Communication**: JSON-RPC over stdin/stdout for LSP.

## Requirements

### Phase 02: Binary Integration (Node.js & Git) [COMPLETED]
- [x] Bundle Node.js and Git binaries via jniLibs.
- [x] Update Native PTY Bridge to inject PATH.
- [x] Implement blocking splash screen for environment verification.

### Phase 03: Project Management & File Operations [COMPLETED]
- [x] Implement slide-in sidebar UI for Project Switching and File Tree.
- [x] Automated per-project directory isolation.
- [x] Integrated ZIP Import/Export for entire projects via Storage Access Framework.
- [x] Added single file upload/download functionality.

### Phase 03.5: Terminal Hardening & DNS [COMPLETED]
- [x] **Shell Function Strategy**: Bypassed Android 10+ `W^X` security policy.
- [x] **DNS Monkey-Patch**: Implemented universal DNS redirection for Node.js.
- [x] Fixed `Permission denied` errors for `git` and `node`.

### Phase 03.6: Native SWC Integration [COMPLETED]
- [x] **Native SWC Support**: Compiled and integrated native SWC binaries for high-performance Next.js builds.
- [x] **Linker Namespace Fix**: Applied `$ORIGIN` patch to all SWC `.so` files.
- [x] **Runtime Symlinking**: Automated symlink creation in `PtyBridge.kt` for seamless Next.js compatibility.

### Phase 04: Native Editor Polish [IN PROGRESS]
- [ ] Implement robust syntax highlighting engine.
- [/] Add support for multiple tabs (ViewModel logic implemented).
- [x] Integrate LSP (Language Server Protocol) via Node.js.

### Phase 05: GitHub OAuth & Source Control [COMPLETED]
- [x] **Native GitHub OAuth**: Implemented a professional Web Redirect flow using deep links (`codeoss://github-auth`).
- [x] **Centralized Git Engine**: Refactored Source Control to use absolute binary paths and a unified environment (fixes "Detached" states).
- [x] **Auto-Stash Switching**: Enabled seamless branch switching by automatically stashing/popping local changes.
- [x] **Live Repository Info**: Added real-time branch detection and Remote URL display in the sidebar.

### Phase 06: Marketplace & Extension Management [COMPLETED]
- [x] **Dynamic GitHub-Driven Store**: Implemented real-time Marketplace fetching from the centralized repository, supporting both NPM and ZIP-based extensions.
- [x] **Progressive UI**: Added live progress bars, instant UI feedback on the Main thread, and single-line compact headers.
- [x] **Project-Aware State**: Smart detection of installed modules within the active project's `node_modules`.
- [x] **Sideloading Fallback**: Allowed manual installation of local ZIP extensions for offline or non-authenticated users.

### Phase 07: Debugging & IDE Polish [COMPLETED]
- [x] **Run & Debug Execution**: Wired up the Debug panel to execute `npm run dev` within the active PTY session.
- [x] **Next.js Auto-Injection**: Implemented smart detection of Next.js projects via `package.json` to automatically inject the `--no-turbo` flag.
- [x] **Clone Progress UI**: Added live progress parsing to display a linear progress bar natively in the IDE.
- [x] **Persistent Notifications**: Integrated Android NotificationManager to display ongoing and successful cloning operations.
- [x] **Granular Font Controls**: Separated `Terminal` and `Editor` font sizes into independent configurable states.

## Out of Scope
- [ ] Web-based UI / WebView support (this is a pure native project).
- [ ] Cloud sync (V1 focuses on 100% local operation).

## Key Decisions
| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Jetpack Compose | Modern Android standard with built-in Skia support. | Validated |
| Bundled Node.js | Necessary for running VS Code extension host and LSPs. | Validated |
| PTY Bridge | Required for a "real" shell experience in the terminal. | Validated |
| **Shell Functions** | **Crucial bypass for Android 10+ security (prevents Permission Denied on internal binaries).** | **Validated** |
| DNS Redirect | Overrides broken system DNS lookups for stable npm/git networking. | Validated |

## Evolution
This document evolves at phase transitions and milestone boundaries.

---
*Last updated: 2026-05-10 after Debugging & UI Enhancements*
