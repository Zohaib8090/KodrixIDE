# Requirements — CodeOSS Android

## High-Level Goals
Build a 100% native Android IDE with a Skia-powered text engine, bundled Node.js/Git, and full terminal support.

## Table Stakes (Must-Have)
- [ ] **Native Editor UI**: Custom Canvas-based text rendering in Jetpack Compose.
- [ ] **File System Access**: Local project folder management (Storage Access Framework).
- [ ] **Node.js Integration**: Ability to start/stop Node.js runtime and run scripts.
- [ ] **LSP Support**: Syntax highlighting, autocomplete, and go-to-definition via LSP.
- [ ] **Integrated Terminal**: Fully functional shell with PTY support.
- [ ] **Git Support**: Basic operations (clone, pull, push, commit, branch).

## Differentiators (Should-Have)
- [ ] **High Performance**: Smooth scrolling with 1M+ line files.
- [ ] **Extension Host**: Running standard VS Code extensions natively.
- [ ] **Multi-Window Support**: Split screen for multiple files or side-by-side terminal.
- [ ] **Custom Themes**: Import/export VS Code themes.

## Technical Constraints
- Target Android 10+ (API 29).
- ARM64-v8a architecture focus.
- 100% Offline capability.

## Success Criteria
- App opens a local folder and renders a file in < 2 seconds.
- Typing is lag-free (sub-16ms latency).
- Terminal responds to `ls`, `git status`, and `node -v` correctly.
