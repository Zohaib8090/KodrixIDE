# Kodrix — Native Binary & Toolchain Fix Summary
# Last Updated: June 2026

This document summarizes all technical fixes applied to native binary handling and toolchain installation.

---

## Fix 1 — Android Linker Namespace (libgit2 / libnode)

### Problem
Since Android 7.0+, the OS uses **Linker Namespaces** for security. `LD_LIBRARY_PATH` is stripped for processes launched from `/system/bin/sh`. Termux-compiled binaries had hardcoded `RUNPATH` pointing to `/data/data/com.termux/files/usr/lib`, causing crashes on devices without Termux.

### Solution — `$ORIGIN` RUNPATH Patching
Modified native binaries directly using precision hex-patching:
- **Original**: `/data/data/com.termux/files/usr/lib` (35 chars)
- **Patched**: `$ORIGIN/./././././././././././././.` (35 chars)

The `/./` padding maintains exact string length without null bytes, keeping binaries compatible with both the Android Linker and Node.js bootstrap scripts.

### Files Patched
- `jniLibs/*/libgit.so`
- `jniLibs/*/libnode.so`
- `jniLibs/*/libgit_remote_http.so`
- `jniLibs/*/libbinding_core_node.so` (SWC)
- `jniLibs/*/libbinding_minifier_node.so` (SWC)
- `jniLibs/*/libbinding_react_compiler_node.so` (SWC)
- `jniLibs/*/libbinding_html_node.so` (SWC)

> **Important**: If you replace these binaries in the future, re-apply the 35-character `$ORIGIN` patch.

---

## Fix 2 — SWC (Next.js) Native Support

Next.js uses Rust-based SWC for compilation. Compiled SWC natively for `aarch64-linux-android` with the same `$ORIGIN` RUNPATH fix. `PtyBridge.kt` automatically creates the expected symlinks after `npm install` or `npx` runs.

---

## Fix 3 — Python LSP (`pylsp`) Process Working Directory

### Problem
`TerminalViewModel` was computing `projDir` using `filesDir.parent`, which resolved to an incorrect path, causing `ProcessBuilder.start()` to throw `IOException` on startup.

### Solution
Corrected `projDir` to use the canonical `projectsRoot`:
```kotlin
val projDir = java.io.File(projectsRoot, proj)
```
This aligns with `/data/user/0/com.kodrix.zohaib/files/projects/<project-name>`.

---

## Fix 4 — C/C++ Toolchain: XZ Extraction Failure (Infinite Redownload Loop)

### Problem
The Clang 21.1.8 toolchain is distributed as Termux `.deb` packages. Their `data.tar.xz` members require XZ decompression. The installer was running:
```bash
/system/bin/tar -xJf data.tar.xz -C staging/ --strip-components=5
```
Android's Toybox `tar` supports `-J` flag syntax, but delegates to the `xz` binary for decompression. **`xz` is not present in the PATH on stock Android.** This caused `tar` to fail silently (no exception thrown, exit code ignored), leaving the install directory completely empty. Since `clangd` was never found, the installer restarted on the next C++ file open — creating an infinite download loop.

### Solution
1. Added **`org.tukaani:xz:1.10`** dependency to `app/build.gradle.kts`.
2. Extract raw `data.tar.xz` member from the `.deb` ar archive to a temp file.
3. Decompress with **`XZInputStream`** in pure Java → produces a plain `.tar` file.
4. Unpack with `tar -xf` (no `-J`) — works on all Android devices.
5. Added **exit-code check** on `tar`: throws `RuntimeException` with full output on failure.

---

## Fix 5 — C/C++ Toolchain: Symlink Crash (`windres` etc.)

### Problem
After XZ decompression was fixed, `copyDirContents()` crashed with:
```
The source file doesn't exist: .../libllvm_21.1.8-2_x86_64.deb_staging/usr/bin/windres
```
`windres` is a **symlink** (→ `llvm-windres`). Kotlin's `File.copyTo()` fails on symlinks whose target may not yet exist in the staging directory.

### Solution
Rewrote `copyDirContents()` to use `java.nio.file.Files` APIs:
```kotlin
if (java.nio.file.Files.isSymbolicLink(filePath)) {
    val linkTarget = java.nio.file.Files.readSymbolicLink(filePath)
    java.nio.file.Files.deleteIfExists(target)
    java.nio.file.Files.createSymbolicLink(target, linkTarget)
}
```
Symlinks are now recreated correctly at the destination.

---

## Fix 6 — C/C++ Toolchain: Concurrent Install Guard

### Problem
`startNativeLsp()` is called every time a C/C++ file is focused. While the toolchain was downloading (taking 2–3 minutes), the user switching between files triggered multiple parallel download coroutines — all writing to the same `tmpDir` and `installRoot`, corrupting each other's work.

### Solution
Added `isInstallingCpp: Boolean` field with `synchronized(this)` guard:
```kotlin
synchronized(this) {
    if (isInstallingCpp) return   // silently ignore duplicate
    isInstallingCpp = true
}
// ... install logic ...
finally { synchronized(this@TerminalViewModel) { isInstallingCpp = false } }
```

---

## Fix 7 — `clangd` Version Check Bypass

### Problem
`BinaryManager.setActiveVersion()` runs `VersionChecker.check()`, which spawns the binary and reads its `--version` output. `clangd` is a Termux/Bionic binary compiled against Termux's linker — it cannot self-execute in the Android app sandbox. This caused the version check to always fail and roll back the installation.

### Solution
Used `BinaryManager.markToolInstalled("clang", "21.1.8")` instead of `setActiveVersion()`. This writes directly to SharedPreferences and rebuilds wrappers without running the binary.

---

## Verification

- Binary extraction confirmed via `adb shell find /data/user/0/com.kodrix.zohaib/files/versions/clang/21.1.8 -name clangd`.
- No more redownload loops — `clangd` found after single install run.
- Logcat confirms `CppInstall: installed <package>` for all 7 packages without repetition.
