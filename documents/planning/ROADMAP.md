# Roadmap — CodeOSS Android

## Milestone 1: Core Native Environment
Focus on setting up the native Android foundation, terminal, and bundled binaries.

### Phase 1: The Shell Foundation
- [ ] Initialize Android Project (Kotlin/Jetpack Compose).
- [ ] Implement PTY bridge for native shell access.
- [ ] Create basic Terminal UI component.
- [ ] Verify local shell execution (`ls`, `cd`, `mkdir`).

### Phase 2: Binary Integration (Node.js & Git)
- [x] Bundle Node.js (ARM64) and Git binaries in assets.
- [x] Implement binary extraction and execution logic.
- [x] Add `node` and `git` to the terminal environment path.
- [x] Verify `node -v` and `git --version` in the integrated terminal.

### Phase 3: IDE Core & Project Management [COMPLETED]
- [x] Create a slide-in sidebar UI for project switching.
- [x] Implement file tree navigation with directory expansion.
- [x] Refine project isolation (Set HOME/PWD in native shell).
- [x] Implement ZIP Import/Export for entire projects.
- [x] Implement local file loading/saving in the editor.

### Phase 3.5: Terminal Hardening & DNS [COMPLETED]
- [x] Implement Shell Function strategy for Android 10+ binary execution.
- [x] Implement DNS monkey-patch for Node.js internet access.
- [x] Fix `git clone` permission and connectivity issues.

### Phase 3.6: Native SWC Integration [COMPLETED]
- [x] Compile native SWC binaries for `aarch64-linux-android`.
- [x] Apply surgical 35-character `$ORIGIN` RUNPATH patch to binaries.
- [x] Implement automatic symlinking in `PtyBridge.kt` for Next.js support.
- [x] Verify `next build` compatibility on Android.

### Phase 4: Language Server Protocol (LSP) [IN PROGRESS]
- [/] Implement Kotlin JSON-RPC client (LspClient engine).
- [x] Bridge TerminalViewModel to background Node.js language servers.
- [x] Implement CodeEditor.kt UI for real-time Diagnostics and Autocomplete.
- [ ] Implement text-insertion logic for completion resolution.
- [ ] Add visual feedback (red wavy underlines) for diagnostics.

### Phase 5: Extension Host & Marketplace [COMPLETED]
- [x] Implement dynamic GitHub-driven Marketplace.
- [x] Support NPM and ZIP-based extension installation.
- [x] Implement real-time installation progress tracking.
- [x] Fixed absolute paths for robust NPM execution.
- [x] VS Code Extension Host bridge integration (Initial).
