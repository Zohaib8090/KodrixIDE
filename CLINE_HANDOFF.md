# Kodrix — Project Handoff

Hello! You are taking over development for **Kodrix**, a native Android IDE that runs entirely on-device — no Termux, no PC, no remote server required.

---

## Architecture Overview

- **App Stack:** Native Android (Kotlin, Jetpack Compose).
- **Embedded Node.js v25:** Bundled as `libnode.so` in `jniLibs/`. Used for JS/TS/HTML/CSS/JSON/Bash LSPs and running web apps.
- **Embedded Python 3.13:** Downloaded from GitHub Releases as a `.zip` and extracted by `BinaryManager`. Used for Python execution and `pylsp`.
- **Clang 21.1.8 (C/C++):** Downloaded on-demand from Termux package repositories as `.deb` files. Extracted using pure-Java XZ decompression (`org.tukaani:xz`) and Toybox `tar`.
- **LSP Bridge:** Language servers run in background processes. The app communicates via STDIO (stdin/stdout) using JSON-RPC 2.0. See `LspClient.kt`.
- **Git Bridge:** `libgit2.so` JNI bridge for clone/commit/push/pull over HTTPS.
- **Native SWC:** Bundled native SWC binaries for Next.js high-performance compilation.

---

## Current State (June 2026 — Phase 5 Complete)

| Feature | Status |
|---------|--------|
| Terminal (PTY) | ✅ Complete |
| Git / GitHub OAuth | ✅ Complete |
| Node.js + npm | ✅ Complete |
| Python 3.13 runtime | ✅ Complete |
| Next.js / React / Vite | ✅ Complete |
| Built-in browser + DevTools | ✅ Complete |
| Port forwarding | ✅ Complete |
| Source control UI | ✅ Complete |
| Extension marketplace | ✅ Complete |
| LSP (HTML/CSS/JS/TS/Bash) | ✅ Complete |
| Python LSP (`pylsp`) | ✅ Complete |
| **C/C++ LSP (`clangd` 21.1.8)** | ✅ Complete |
| AI Agent (Gemini) | ✅ Complete |
| React Native | ❌ Not started |
| Flutter | ❌ Not started |

---

## C/C++ Toolchain — Important Notes

The C/C++ toolchain is installed on-demand when the user first opens a `.c` or `.cpp` file.

**Key implementation details:**

1. **Download**: ~120–150 MB of Termux `.deb` packages from `packages-cf.termux.dev`.
2. **XZ decompression**: Android's system `tar` supports `-J` but requires the `xz` binary in PATH — which is NOT present on stock Android. The installer uses `org.tukaani:xz` (`XZInputStream`) to decompress in pure Java, then calls `tar -xf` (no `-J` flag).
3. **Symlinks**: Termux packages contain symlinks (e.g. `windres → llvm-windres`). `copyDirContents()` uses `java.nio.file.Files.isSymbolicLink()` + `Files.createSymbolicLink()` — NOT `File.copyTo()` — to handle these.
4. **Verification skip**: `clangd` is a Termux/Bionic binary that cannot self-execute in the Android sandbox. `BinaryManager.markToolInstalled()` is used to bypass the version check.
5. **Concurrency guard**: `isInstallingCpp` (synchronized) prevents duplicate download coroutines if the user switches between `.cpp` files during install.

**Install path**: `filesDir/versions/clang/21.1.8/usr/bin/clangd`

---

## Key Files to Review

| File | Purpose |
|------|---------|
| `viewmodel/TerminalViewModel.kt` | LSP lifecycle, process launch, C++ toolchain install, Python pylsp install |
| `bridge/BinaryManager.kt` | Binary download/extract/versioning, notification progress, wrapper scripts |
| `lsp/LspClient.kt` | JSON-RPC STDIO communication bridge |
| `lsp/LspTypes.kt` | LSP protocol data classes |
| `ui/IDEView.kt` | Editor UI, autocomplete dropdown rendering |
| `app/build.gradle.kts` | Gradle dependencies (`org.tukaani:xz:1.10` added for C++ install) |

---

## Build & Deploy

```bash
# Build
./gradlew assembleDebug

# Install (replace with your device serial)
adb -s <DEVICE> install -r app/build/outputs/apk/debug/app-x86_64-debug.apk

# Monitor logs
adb -s <DEVICE> logcat | grep -E "Kodrix|LspClient|CppInstall"
```

---

## Next Tasks

1. Verify `clangd` autocomplete end-to-end after toolchain install completes on real hardware.
2. Add `compile_commands.json` generation so `clangd` resolves project-specific include paths.
3. React Native support.
4. Flutter support.

Good luck!
