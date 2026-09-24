# Phase 01 Research: The Shell Foundation

## Objective
Implement a high-performance native terminal emulator for Android using Jetpack Compose and a JNI-based PTY bridge.

## Technical Analysis

### 1. Native PTY Bridge (C/C++)
Android's Bionic library does not expose `forkpty` directly. We must implement a wrapper using `openpty`, `fork`, and `login_tty`.
- **Master/Slave Pair**: Create a PTY pair to separate the control (master) from the execution (slave).
- **Process Spawning**: Use `execve` to launch `/system/bin/sh` or a custom shell if available.
- **Signal Handling**: Must handle `SIGWINCH` to allow the shell to resize correctly when the Android device rotates or the soft keyboard appears.

### 2. Terminal Emulation (libvterm)
We will use `libvterm`, a lightweight, state-based terminal emulator library.
- **JNI Interface**: The Kotlin layer will send byte streams from the PTY to `libvterm`.
- **Callbacks**: `libvterm` will notify the app of screen updates (text changes, scrolling, cursor movement).
- **State Machine**: `libvterm` handles complex ANSI escape sequences, colors, and styling, which simplifies the Kotlin UI layer.

### 3. Jetpack Compose Rendering
To maintain 60/120fps, we cannot use standard `Text` components for the terminal grid.
- **Canvas Rendering**: Use `drawText` or `drawRawText` within a `Canvas` composable.
- **Grid Optimization**: Maintain a 2D array of "Cells" (character + attributes) in Kotlin. Only re-draw the cells that have changed or are currently visible.
- **Input Handling**: Capture hardware keys and virtual keyboard input, converting them to escape sequences (e.g., Arrow Keys -> `\e[A`) before sending to the PTY.

### 4. NDK Setup
- **CMake**: Use CMake to compile the native bridge and link `libvterm`.
- **ABI Support**: Target `arm64-v8a` and `x86_64` (for emulators).

## Validation Architecture
- **Unit Tests**: Test the JNI bridge's ability to spawn a process and read output.
- **Integration Tests**: Verify that escape sequences (like `\e[31m` for red) are correctly interpreted by `libvterm`.
- **Manual Verification**: Run `top`, `ls -R`, and `vi` to ensure terminal compatibility.

## Reference Implementations
- **ConnectBot terminal-compose**: Primary reference for `libvterm` + Compose.
- **Termux-app**: Reference for native PTY handling on Android.

## Potential Gotchas
- **SELinux**: Android's SELinux policy may restrict `fork` or `exec` in certain contexts. We must ensure the app runs in a context that allows shell execution.
- **UTF-8 Support**: Ensure `libvterm` and the JNI layer correctly handle multi-byte characters.
