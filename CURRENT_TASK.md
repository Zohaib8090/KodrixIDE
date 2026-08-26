# Kodrix — Current Session & Task Status
# Last Sync: 2026-06-26

This file serves as a persistent record of the project's progress, ensuring context is preserved across AI sessions.

---

## Current Milestone
**Milestone 2: Language Intelligence & Native Toolchains**

---

## Completed Phases

### Phase 3 — Core Native Environment (✅ Complete)
- [x] Native SWC Integration — compiled `aarch64-linux-android` binaries, patched with `$ORIGIN` RUNPATH logic for Next.js.
- [x] PtyBridge Symlinks — automated SWC symlink creation in `node_modules` after npm runs.
- [x] Multi-arch APK splits — `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

### Phase 4 — LSP Autocomplete (✅ Complete)
- [x] LSP Client Completion — prefix backtracking to prevent duplicate text insertion.
- [x] Syntax Highlighting — red/orange underlines driven by LSP diagnostics.
- [x] Multi-Tab Polish — suggestion cleanup on tab switch and closure.
- [x] Python LSP (`pylsp`) — zero-config background pip install with `--no-deps` workaround for ujson.
- [x] LSP path bug fix — corrected `projDir` to use `projectsRoot` so ProcessBuilder starts in the correct project directory.

### Phase 5 — C/C++ Toolchain (✅ Complete — June 2026)
- [x] `installCppToolchain()` — on-demand download of Clang 21.1.8 + 6 dependency packages from Termux repositories.
- [x] Pure-Java XZ decompression — replaced broken `tar -xJf` (no system `xz` on Android) with `org.tukaani:xz` `XZInputStream` in Java, then `tar -xf` on the plain `.tar`.
- [x] Symlink-aware `copyDirContents()` — uses `java.nio.file.Files.isSymbolicLink()` + `Files.createSymbolicLink()` to handle `windres → llvm-windres` and other symlinks in the clang package.
- [x] Concurrency guard — `isInstallingCpp` synchronized flag prevents multiple parallel download coroutines.
- [x] `tar` exit-code check — throws with full output if extraction fails, ending the silent-failure loop.
- [x] `BinaryManager.markToolInstalled("clang", "21.1.8")` — skips VersionChecker (clangd can't self-exec in the Android sandbox); writes active version to prefs and rebuilds wrappers.
- [x] `clangd` LSP startup — full `startNativeLsp` flow for `.c`/`.cpp` after toolchain install completes.

---

## Active Phase
**Phase 6 — Polish & Stability**

### Pending / Next Up
- [x] Integrate JetBrains JediTerm + Pty4J in desktopApp using SwingPanel for native, high-performance local terminal emulation.
- [x] Verify `clangd` autocomplete end-to-end after toolchain extraction completes on real hardware.
- [x] Add `compile_commands.json` generation support so `clangd` resolves project-specific include paths.
- [ ] React Native support.
- [ ] Flutter support.

---

## Key Files

| File | Purpose |
|------|---------|
| `viewmodel/TerminalViewModel.kt` | LSP lifecycle, process orchestration, toolchain installation |
| `bridge/BinaryManager.kt` | Binary download, extraction, versioning, wrapper scripts |
| `lsp/LspClient.kt` | JSON-RPC STDIO bridge |
| `lsp/LspTypes.kt` | LSP data classes |
| `ui/IDEView.kt` | Editor UI, autocomplete dropdown |
| `app/build.gradle.kts` | Dependencies (incl. `org.tukaani:xz:1.10` for C++ install) |

---

## Dependency Notes

| Dependency | Version | Reason |
|------------|---------|--------|
| `org.tukaani:xz` | `1.10` | Pure-Java XZ decompression for Clang `.deb` packages |
| `com.google.code.gson:gson` | `2.10.1` | LSP JSON-RPC serialization |
| Node.js | v25 | JS/TS/HTML/CSS/JSON/Bash LSPs |
| Python | 3.13.13 | Python runtime + pylsp |
| Clang/clangd | 21.1.8 | C/C++ compiler + LSP |
