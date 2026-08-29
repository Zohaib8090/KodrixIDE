# Debug Kotlin Crash

When the user reports a Kotlin/Android crash:

1. **Find the crash** — ask for the logcat output, or for the
   `hs_err_pid*.log` file if it was a JVM-level SIGSEGV.
2. **Read the stack** — top frame is the failure site. Note the
   thread name; `DefaultDispatcher-worker-N` means a coroutine.
3. **Classify**:
   - `NullPointerException` — null deref; find the variable
   - `IndexOutOfBoundsException` — index math bug
   - `IOException` — file/network/permission issue
   - `RuntimeException` from native — likely a JNI bug, check for
     use-after-free, fd leaks, or null pointer returns from JNI
4. **Propose a fix** — concrete code change, not just a description.
   Show the before/after diff.
5. **Verify** — explain how the user can confirm the fix works
   (logcat tags to watch, what to expect on next run).

Common patterns in this codebase:
- `libnode_bin.so`, `libgit2.so` etc are JNI libs loaded at startup
- `NativeLibLoader.kt` copies libs from APK to `filesDir/lib/` and
  loads them in a specific order
- `PtyBridge` exposes a real terminal via forkpty; SIGSEGV in the
  native read usually means the fd was closed on another thread
