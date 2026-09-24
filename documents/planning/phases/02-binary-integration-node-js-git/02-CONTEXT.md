# Phase 02: Binary Integration (Node.js & Git) - Context

**Gathered:** 2026-04-25
**Status:** Ready for planning

<domain>
## Phase Boundary
This phase focuses on bundling, extracting, and executing Node.js and Git binaries within the Android application. It concludes when the integrated terminal can successfully execute `node -v` and `git --version` with output displayed in the UI.

</domain>

<decisions>
## Implementation Decisions

### Binary Distribution
- **Strategy**: `jniLibs` approach. Binaries will be stored as `libnode.so` and `libgit.so` in `app/src/main/jniLibs/arm64-v8a/`.
- **Source**: Termux packages repository (aarch64).
- **Extraction**: Android handles extraction to the native library directory at install time.

### Environment Integration
- **PATH Injection**: The `native-lib.cpp`'s `createPty` function will be modified to set the `PATH` environment variable to include the app's `nativeLibraryDir`.
- **LD_LIBRARY_PATH**: Will also be updated to the library directory to ensure any shared library dependencies are found.
- **Home Directory**: Set to the app's internal `filesDir`.

### User Experience
- **Splash Screen**: A one-time blocking splash screen will be implemented to ensure binaries are ready before the user enters the IDE/Terminal.

### the agent's Discretion
- Handling of shared library dependencies (bundling necessary `.so` files from Termux if the main binaries are not statically linked).
- UI design of the Splash screen (using the project's dark aesthetic).

</decisions>

<canonical_refs>
## Canonical References
- `.planning/PROJECT.md`
- `.planning/REQUIREMENTS.md`
- [Termux Packages](https://github.com/termux/termux-packages)

</canonical_refs>

<deferred>
## Deferred Ideas
- Phase 3: Skia-based editor engine.
- Phase 4: LSP Integration (using the newly bundled Node.js).

</deferred>

---
*Phase: 02-binary-integration-node-js-git*
*Context gathered: 2026-04-25 after discussion*
