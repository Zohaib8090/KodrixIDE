# Plan - Phase 02: Binary Integration (Node.js & Git)

This phase integrates Node.js and Git binaries into the CodeOSS Android environment. We will use the `jniLibs` strategy to bypass Android 10+ execution restrictions and implement a splash screen to ensure a smooth first-run experience.

## User Review Required

> [!IMPORTANT]
> **Binary Dependencies**: Termux binaries are dynamically linked. To ensure they run in your app, I will bundle the primary binaries and their core shared library dependencies (e.g., `libandroid-support.so`, `libssl.so`, etc.).
> 
> **Binary Acquisition**: I will provide a script to download and prepare these binaries. You will need to run this script in your local terminal (since I cannot download and move files into `src/main/jniLibs` directly in one step without potentially hitting environment limits).

## Proposed Changes

### [Component] Binary Management (Scripted)

I will create a PowerShell script `scripts/fetch-binaries.ps1` to:
1. Download Node.js and Git `.deb` packages from the Termux repository.
2. Extract the binaries and shared libraries.
3. Rename executables to `.so` (e.g., `node` -> `libnode.so`) for Gradle compatibility.
4. Move them to `app/src/main/jniLibs/arm64-v8a/`.

---

### [Component] Native Bridge (C++/JNI)

#### [MODIFY] [native-lib.cpp](file:///c:/Users/zohai/Downloads/my code/KodrixIDE/app/src/main/cpp/native-lib.cpp)
- Update `createPty` to accept `binPath` and `libPath` as arguments.
- Use `setenv` to update `PATH` and `LD_LIBRARY_PATH` in the child process before `exec`.
- Ensure `HOME` is set to the app's internal files directory.

---

### [Component] Terminal Logic (Kotlin)

#### [MODIFY] [PtyBridge.kt](file:///c:/Users/zohai/Downloads/my code/KodrixIDE/app/src/main/kotlin/com/zohaib/codeossandriod/PtyBridge.kt)
- Update `startShell` to resolve the app's `nativeLibraryDir`.
- Pass paths to the native `createPty` function.

---

### [Component] UI / UX (Jetpack Compose)

#### [MODIFY] [MainActivity.kt](file:///c:/Users/zohai/Downloads/my code/KodrixIDE/app/src/main/kotlin/com/zohaib/codeossandriod/MainActivity.kt)
- Implement a `SplashScreen` state.
- Perform a "First Run" check:
    - Verify if `libnode.so` and `libgit.so` are present in the library directory.
    - Test execution by running `libnode.so --version` in a background process.
- Transition to `IDEView` once ready.

## Verification Plan

### Automated Tests
- Terminal verification: Run `node -v` and `git --version` via the `TerminalViewModel` and assert that the output contains version strings.

### Manual Verification
- Launch the app.
- Confirm the splash screen appears on the first run.
- Open the terminal and type `node` and `git` to ensure they are available in the PATH.
