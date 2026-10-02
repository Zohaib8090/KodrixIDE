# Gotchas

Things that already bit us in this project. Check here before debugging something odd.

## Android / running programs

- **Android 10+ blocks `execve()` on ANY file in app storage** (targetSdk ≥ 29, SELinux
  `execute_no_trans`) — ELFs **and wrapper scripts**, even `#!/system/bin/sh` ones. A
  downloaded ELF fails with "Permission denied"; start it as `/system/bin/linker64 <elf> args`
  (`/system/bin/linker` for 32-bit). A script must be run *through* its interpreter
  (`/system/bin/sh script`). Only binaries inside the APK (`nativeLibraryDir`) run directly.
  See `runtime/RuntimeExec.kt`.
  *(An earlier version of this file said scripts were fine. That was wrong, and it caused the
  `usr/bin/git: Permission denied` regression below.)*
- **`usr/bin/git` / `node` must be symlinks into the APK's native lib dir, not scripts.**
  Commit 99d6e8f ("faster startup") stopped re-creating those symlinks on every terminal
  start, which had been silently hiding that the wrappers were scripts. Fixed in PR #9:
  bundled tools are real symlinks, and `init.sh` defines a **shell function per remaining
  wrapper script** (npm, npx, gh, downloaded runtimes, hint messages) that runs it via
  `/system/bin/sh`. So typing a command at the prompt works, but anything that `execve()`s a
  wrapper by path (a build tool, `npm run`) still can't.
- **Changing startup work can unmask old bugs.** Before deleting "redundant" setup, check what
  it was quietly fixing.
- **Termux binaries hardcode `/data/data/com.termux/files/usr`.** Remapped by the shim via
  `KODRIX_USR`. Absolute paths inside installed files (shebangs, symlinks) are rewritten at
  install time, so an install dir **cannot be moved** after extraction — extract straight
  into the final folder.
- **glibc-style programs call the `*64` variants** (`open64`, `stat64`, `fopen64`, …). The shim
  must hook those too or path remapping silently does nothing for Python.
- **Go and Zig make raw syscalls**, so the `LD_PRELOAD` shim can't see their child processes.
  `go build` / `go run` likely fail; both are marked experimental.
- **`/usr/bin/env` doesn't exist** on Android (it's `/system/bin/env`), and Termux scripts
  often use `#!…/bin/bash` which we don't ship — rewritten to `/system/bin/sh`.
  Commands are often symlinks into `lib/` (npm), so the shebang fix follows links.
- **`/` is not readable by apps.** A shell starting there says `ls: .: Permission denied`.
  Start in the projects folder.
- **Never put a downloaded runtime's raw `bin/` on PATH.** Only the `usr/bin` wrappers (via
  the `init.sh` functions above), which know how to start it.
- **Don't spawn `ln -sf` (or any process) at startup to make links.** It's slow, and it
  overwrote the Node/Git wrappers on every terminal start, so a chosen Node version silently
  reverted to the built-in 25.8.2. Use `Os.symlink`, and leave existing regular files alone.

- **`files/lsp/package.json` was written truncated** after the codebase migration, so Node
  refused to run anywhere under `files/lsp` (`ERR_INVALID_PACKAGE_CONFIG`) and every language
  server install failed with "exit 1". It is now valid JSON and rewritten if broken; npm's
  output goes to `files/lsp/install.log` and the toast shows the real error. If LSPs fail on
  start, read that log first.
- **npm uses `usr/bin/sh` as its shell (`NPM_CONFIG_SHELL`)**, itself a script in app storage.
  `npm install` works; `npm run <script>` failing with "Permission denied" is why.

## Termux packages

- **Mirrors lag each other.** Download the `.deb`s from the same mirror that served the index
  you resolved against.
- **Versions can contain `:` (epoch), e.g. `3:1.27.1`** — breaks PATH and dir names. Use
  `BinaryManager.versionKey()` for folders, `displayVersion()` for humans.
- **A `.deb` is an `ar` archive**; files are under `./data/data/com.termux/files/usr/`.
  `xz` isn't on Android — extraction is pure Kotlin (`org.tukaani:xz`).
- **Not every package exists for every CPU** (Crystal, Deno, Bun, Swift, Dart, marksman are
  missing on some). Hide the entry on those CPUs; the weekly check reports it as info.
- **Some launchers need Termux's bash** (e.g. `lua-language-server`) — run the real binary
  directly instead of the wrapper script.
- **bash-language-server uses `start`, not `--stdio`.** npm lives at `npm_pkg/bin/npm-cli.js`
  (not `lib/node_modules/npm/bin/…`). JS language servers always run on the **built-in** Node,
  never a downloaded one.

## Registry (`KodrixMarketplace/versions.json`)

- **`main` of KodrixMarketplace is live for every installed copy of the app.** Work on the
  branch; merge deliberately.
- **Old app builds crash on entries without `version`/`tag`.** Termux entries still carry
  placeholders. Old zip entries are kept and marked `"legacy": true` (new builds skip them).
- `_`-prefixed keys are settings, not tools. A registry entry with the same id as a catalog
  entry **replaces** it, so hints/env must be kept in both places (`BuiltinCatalog.kt` and
  `versions.json`).
- "Current" is Node's own jargon for its newest line — show **Latest / LTS / Built-in**.

## Kotlin / Compose

- **Init order:** a property used by an `init {}` block must be declared *above* it
  (`terminalEnvLock` crashed/NPE'd risk). `by lazy` properties are fine.
- **Name shadowing breaks builds quietly:** a local `settings` in `BrowserView` hid
  `WebView.settings` inside `WebView(context).apply { settings.apply {…} }` → dozens of
  "Unresolved reference" errors. Prefer distinctive local names.
- **`imePadding()` on the root resizes everything** for the keyboard, pushing the terminal up
  when typing in the side panel. It's switched off while a sidebar field has focus.
- `TerminalSession` needs the main thread (creates a Handler); do the slow prep on IO first.
- Encrypted prefs (`binary_manager_secure`) are slow to open — keep them lazy.

## Build & tooling

- **Windows PC build** (full recipe in `HANDOFF.md` §2.1):
  - The build pins NDK `30.0.14904198` (an r30 beta); Android Studio only offers a newer one.
    Install the pinned one with the Android CLI — `sdkmanager.bat` mis-parses the `;` in
    `ndk;…`.
  - `local.properties` needs **forward slashes** (`sdk.dir=C\:/Users/...`); single backslashes
    are escape characters.
  - **"Unable to establish loopback connection"** = Java 17 + Unix-domain sockets under a long
    `%TEMP%`. Run `./gradlew` from Git Bash with `TEMP`/`TMP` and
    `-Djdk.net.unixdomain.tmpdir` pointed at a short dir like `C:/gtmp`, and disable the Bash
    sandbox (Gradle needs loopback sockets).
- **The app module must compile as Java 17.** With Java 8, D8 fails on the `record` classes in
  Sora's TextMate library ("Record desugaring"); library desugaring didn't help.
- **Sora Editor is pinned to 0.24.4.** 0.24.5+ needs compileSdk 36 and Kotlin 2.3; this project
  is compileSdk 34 / Kotlin 2.1.0.
- `androidApp/src/main/assets/textmate/` is **generated** (`node scripts/build-textmate-assets.mjs`)
  — don't edit it by hand.
- Chat/file limits when sharing with the owner: files over ~30 MB can't be sent through chat
  (the arm64 APK is ~173–229 MB) and a sent `.md` didn't open on the phone — share the Actions
  run link or a GitHub URL instead. The owner is often on **mobile data**: say download sizes.

- **No Android SDK in the cloud session**, so the CI run *is* the compile check. Pure-JVM
  code (`TermuxRepo`, `BuiltinCatalog`) can be tested locally with `kotlinc` + `xz.jar` +
  `org.json`.
- A `.gitignore` rule `*.txt` once swallowed `CMakeLists.txt` — the build failed only in CI.
- Maven Central rate-limits (429) and returns a **text file with a `.jar` name** — check
  `unzip -l` before trusting a download.
- CI logs from the GitHub tool can be huge single-line JSON; parse them with python and grep
  for ` e: ` to find Kotlin errors.
- Debug APK is built per ABI; the owner's phone needs **`arm64-v8a`** (not universal).

## Process

- **Ask who gets credit before every commit + push** (Only me / Me + Claude / Only Claude).
  The sandbox's default git identity is `Claude <noreply@anthropic.com>`; set author/committer
  explicitly when the owner is credited.
- Work on `claude/confident-rubin-3wc23a`; merge to `main` only when the owner says so, via a
  PR (`gh pr create` + `gh pr merge --merge`). New features wait on the branch until the owner
  has tested the CI APK; experimental ones go behind **Settings → Developer**, off by default.
- **Pull before you push.** The owner also commits from a Windows PC and from Termux; a plain
  push from a stale clone is rejected. Fetch, `git rebase origin/<branch>`, then push — never
  force-push the shared branch.
- Say "owner" (not "maintainer") in docs and messages.
- Never claim a language is "supported" unless its **language server works too**.
- On-device behaviour (linker launch, LSPs, startup time, keyboard) is **unverified until the
  owner tests** on the Samsung (Android 14, arm64).
