# Kodrix — Handoff (cloud → local)

Last updated: 2026-09-27 (local Windows session: terminal git and language-server fixes
verified on device; security/community docs; VS Code extensions plan; Phase A experimental
editor built, awaiting device test).
Originally written at the end of a long cloud session so work can continue locally. Read this
first; details live in the linked docs.

---

## 0. The mission (what we're building and why)

In the owner's words: *"install the application, go, select, do my coding thing and all,
done."* Kodrix should be **one Android app where anyone can code in many languages**, with
none of the setup a phone normally needs (no Termux app, no manual installs, no config).

What that means in practice (every change should move toward these):

1. **A language "works" only when both halves work:** it runs in the terminal *and* the
   editor has its language server (autocomplete, errors, go-to-definition). Never advertise
   a language as supported if its autocomplete doesn't work; say so honestly instead.
2. **Out of the box:** JavaScript/TypeScript (built-in Node), HTML/CSS/JSON, Shell and Git
   work with no downloads. Everything else is **one tap** in Marketplace → Runtimes, which
   installs the runtime *and* its language server.
3. **The app is independent of runtimes:** new languages, new versions and updates must not
   need an app update or anyone hand-editing the marketplace. Versions come live from the
   package repository; the app carries a built-in catalog; the online registry only adds or
   overrides; several mirrors are tried so one outage doesn't break installs.
4. **Only what you use is loaded:** installed runtimes can be **paused** (nothing in memory,
   commands and language server off) and resumed without re-downloading.
5. **Clear to a non-expert:** plain labels (Built-in / Latest / LTS, not "Current"), and when
   something fails the user sees the full, explained error — never a cut-off stub.
6. **Respectful of the phone:** fast startup (nothing slow on the main thread), permissions
   asked only when needed (camera/mic from the browser, with separate buttons and Skip), the
   keyboard doesn't shove unrelated panels around.
7. **Target device:** owner's Samsung, Android 14 (arm64). Android 10+ forbids running
   downloaded binaries directly — everything must go through the linker64 + shim path
   (`runtime/RuntimeExec.kt`, `kodrix_exec.c`).

Owner's original wish not yet built: when installing a language, *ask* whether to also
install its language server (today it's always installed).

---

## 1. Where the code is

| Repo | Branch with the new work | Merged to `main`? |
|---|---|---|
| `Zohaib8090/KodrixIDE` | `claude/confident-rubin-3wc23a` | **Partly.** `main` = `15f5599` (PRs #9–#12 merged 2026-09-26). The branch is ahead with the VS Code extensions plan and the **Phase A experimental editor (`c933f1a`), not merged until the owner tests it** |
| `Zohaib8090/KodrixMarketplace` | `claude/confident-rubin-3wc23a` | **No** — registry v2, Node from Termux, weekly check |

Earlier work from this session (docs move, AI agent migration, first CI) was merged as PR #8.

To continue locally:

```bash
cd KodrixIDE
git fetch origin
git checkout claude/confident-rubin-3wc23a
git pull
# KodrixMarketplace the same way; merge its branch to main when ready — the app reads
# versions.json from main.
```

**Rules agreed with the owner (keep following them):**
- Keep working on `claude/confident-rubin-3wc23a` in both repos. Merge to `main` only when
  the owner says so, via a PR from the branch (`gh pr create` + `gh pr merge --merge`).
- **Before every commit + push, ask who gets credit:** *Only me* (author Zohaib Baig
  <zohaibbaig144@gmail.com>, no Claude line) / *Me + Claude* (author Zohaib + a
  `Co-Authored-By: Claude` line) / *Only Claude*. Don't push until answered.
- Owner tests on a **Samsung, Android 14, arm64-v8a**.

---

## 2. Build & test

- CI: `.github/workflows/android-apk.yml` builds a debug APK on every push and uploads one
  artifact per ABI (`arm64-v8a` is the one for the owner's phone). ~7–10 min.
- Local: `./gradlew :androidApp:assembleDebug` (needs Android SDK + NDK + CMake; the native
  shim `androidApp/src/main/cpp/kodrix_exec.c` is built by CMake).
- There are no unit tests for the runtime code in the repo; pure-JVM pieces
  (`TermuxRepo`, `BuiltinCatalog`) were tested ad hoc against the live Termux index.

### 2.1 Local build on the owner's Windows PC (works, verified 2026-09-25)
`./gradlew :androidApp:assembleDebug` succeeded in ~3 min. Output lands in
`androidApp/build/outputs/apk/debug/` (`androidApp-arm64-v8a-debug.apk` ≈ 229 MB is the one
for the phone; the universal APK is ≈ 515 MB). Gotchas hit and fixed:
- **NDK version**: the build pins `ndkVersion = "30.0.14904198"` (an r30 beta). Android
  Studio only offered 30.0.16138531. Install the pinned one with the new Android CLI
  (`sdkmanager.bat` mis-parses the `;` in `ndk;…`):
  `"%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\android.exe" sdk install --canary ndk/30.0.14904198`
- **local.properties** (gitignored, create it yourself): use forward slashes, because single
  backslashes are escape characters there:
  `sdk.dir=C\:/Users/Zohaib Baig/AppData/Local/Android/Sdk`
- **"Unable to establish loopback connection"** (Gradle can't talk to its daemon): a Java 17
  on Windows problem with Unix-domain sockets under a long `%TEMP%` path. Fix: run from Git
  Bash with a short temp dir:
  ```bash
  mkdir -p /c/gtmp
  export JAVA_HOME="/c/Program Files/Java/jdk-17" TEMP='C:\gtmp' TMP='C:\gtmp' \
    JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/gtmp -Djava.io.tmpdir=C:/gtmp'
  ./gradlew :androidApp:assembleDebug
  ```
  When Claude runs this, the Bash sandbox must be disabled, because Gradle needs loopback sockets.
  (`cmd /c gradlew.bat …` from PowerShell failed on path quoting; use Git Bash `./gradlew`.)
- **App module compiles as Java 17** (`androidApp/build.gradle.kts`, since 2026-09-26). With
  Java 8, D8 fails on the Java `record` classes in Sora's TextMate library: "Attempt to create
  a global synthetic for 'Record desugaring' without a global-synthetics consumer". Library
  desugaring and `android.enableGlobalSyntheticsGeneration` did not fix it; Java 17 did.
- **Sora Editor version**: 0.24.4. 0.24.5+ requires compileSdk 36 and Kotlin 2.3; this project
  is on compileSdk 34 / Kotlin 2.1.0. Upgrading Sora means upgrading those first.
- No Android emulator is set up on the PC (no system images), so device testing is the
  owner's phone via CI APKs.
- **Termux + proot on the phone**: building the APK there hasn't been tried. The Android
  SDK/NDK don't officially support that host, so use CI (push to the branch and download the
  `arm64-v8a` artifact) or build on the PC.

---

## 3. What was built (newest first)

### 3.0 Session of 2026-09-25 → 27 (local Windows)
- **Phase A experimental editor** (`c933f1a`, branch only, **untested on device**). Settings →
  Developer → *Experimental editor* (off by default) swaps the text area for Sora Editor
  0.24.4 with VS Code TextMate grammars for 14 languages and the Dark Modern theme
  (`ui/SoraCodeEditor.kt`, `androidApp/src/main/assets/textmate/`, regenerate with
  `node scripts/build-textmate-assets.mjs`). Tabs, toolbar, completion dropdown and problems
  panel are unchanged; edits go through `updateEditorText(…, smartIndent = false)`; accepted
  completions are applied as a minimal replace so scroll and undo survive; LSP diagnostics are
  squiggles. CI run 36231296866 passed. Plan: `documents/VSCODE_EXTENSIONS.md` (approved;
  decisions in §11). Next: Phase B (install `.vsix` + themes).
- **Language servers failed on every start, "exit 1"** (PR #12, **owner confirmed fixed**):
  `files/lsp/package.json` was written truncated (since the codebase migration), so Node
  refused to run there (`ERR_INVALID_PACKAGE_CONFIG`). It's now valid and rewritten if broken.
  npm's output goes to `files/lsp/install.log`, and the toast shows the real error.
- **`usr/bin/git: Permission denied` on terminal start** (PR #9, **confirmed fixed**: `node
  v25.8.2`, `npm 11.16.0`, `git 2.54.0` work). Android 10+ won't exec scripts in app storage
  either. Built-in git/node/git-remote-http(s) are symlinks into the APK's native lib dir again
  (`WrapperManager.writeNotInstalledScript`, safe-mode wrappers), and `init.sh` defines a
  shell function per remaining wrapper script so it runs via `/system/bin/sh`. This was a
  regression from the "Faster startup" commit `99d6e8f`.
- **Community/security docs** (PRs #9–#11): `SECURITY.md` (private reporting via GitHub
  advisories, supported = v1.1.2 + main/CI, contributor security rules), `CODE_OF_CONDUCT.md`
  (Contributor Covenant 2.1 based, reports to the owner's email), `CONTRIBUTING.md` (PR
  standards, AI-assisted contributions accepted with disclosure + test results + screenshots),
  `.github/pull_request_template.md`.
- **push-session skill** (`.claude/skills/push-session/`) and Windows build notes (§2.1).

### 3.1 UX fixes (cloud session; keyboard/startup/permissions still to check on device)
- **Keyboard:** focusing a side-panel field (Runtimes search etc.) no longer pushes the
  terminal up; typing in terminal/editor still lifts them. `ui/IDEView.kt` (`sidebarFocused`).
- **Terminal start dir:** with no project open it used `/` (unreadable → `ls: Permission
  denied`); now the projects folder. `TerminalViewModel.startTerminalSession`.
- **Command hints:** typing `rustup` / `rust` explains to use `rustc`/`cargo` and Runtimes
  (catalog `"hints"` → `"message"` wrappers in `WrapperManager`).
- **Faster startup:** editor shows immediately; terminal env, wrapper rebuild, npm extraction
  and file-tree scan run in the background (`prepareTerminalEnvironment`,
  `BinaryManager.prepare()`); keystore prefs are lazy; `ln -sf` spawns replaced by
  `Os.symlink`.
- **Bug fixed:** startup used to overwrite `usr/bin/node` and `git` wrappers every terminal
  start, silently reverting to the built-in Node after the user picked another version.
- **Permissions:** only notifications at startup. Camera/mic are offered in the **browser**
  (card with separate *Allow microphone* / *Allow camera* / *Skip*; toolbar button reopens it;
  pages only get what Android allowed). `ui/BrowserView.kt`.

### 3.2 Runtimes system ("runtime-independent app")
Goal: install any language from Marketplace → Runtimes with its language server, no app
update and no manual registry maintenance.

- **Why downloaded runtimes failed before:** Android 10+ (targetSdk ≥ 29) blocks `execve()`
  of files in app data. Downloaded ELFs are now started as `/system/bin/linker64 <elf>` and
  `libkodrix_exec.so` is `LD_PRELOAD`ed so their child processes are launched the same way and
  Termux's `/data/data/com.termux/files/usr` resolves to the install dir.
  Files: `runtime/RuntimeExec.kt`, `androidApp/src/main/cpp/kodrix_exec.c`.
- **Sources:** `source: "termux"` resolves packages + dependencies from the Termux repo
  (6 mirrors, SHA-256 checked, `.deb` extraction in pure Kotlin) — `runtime/TermuxRepo.kt`.
  Versions are never hard-coded: shown/updated from the live index.
- **Catalog:** `runtime/BuiltinCatalog.kt` ships ~24 languages (Node, Python, C/C++, Rust,
  Go*, Lua, PHP, Zig*, Dart, Gleam, Swift, Ruby, Java, Kotlin, Perl, Elixir, Haskell, Nim,
  Crystal, Deno, Bun, Markdown, TOML). The online `versions.json` adds/overrides by id.
  *experimental: Go/Zig use raw syscalls, so the shim can't redirect their child processes.
- **Search all packages:** Runtimes search also searches all ~3,000 Termux packages;
  non-catalog ones install as `pkg-<name>` with their commands on PATH and an Update entry.
- **Per-install manifest** `kodrix-runtime.json` (`runtime/RuntimeManifest.kt`) drives PATH
  wrappers, env, file types → LSP. LSPs start generically (`TerminalViewModel.startRuntimeLsp`);
  npm-based servers (pyright, intelephense) run on the built-in Node.
- **Pause/Resume** per runtime (commands off PATH, LSP stopped, nothing loaded).
- **UI:** `ui/MarketplaceView.kt` Runtimes tab — Built-in/Latest/LTS labels (no "Current"),
  IN USE / PAUSED, sizes, install stages, full error text on tap, Remove.
- **Registry:** fetched from raw.githubusercontent → jsDelivr → github.com, cached on disk.
- **Weekly check:** `KodrixMarketplace/.github/workflows/check-registry.yml` +
  `scripts/check_registry.py` resolve every entry on 4 CPU types, open/close a
  `runtime-check` issue.
- **Welcome screen** (`ui/WelcomeScreen.kt`) replaced the "install Python?" onboarding.

Design + schema: `documents/LANGUAGE_PACKS.md` (§18 = what shipped, registry schema).

### 3.3 Earlier in the session (merged)
Docs moved to `documents/`; bug sweep; legacy `AgentOrchestrator` removed in favour of the
AutoAgent/AgentPanel system; shell-injection fix; CI APK workflow; Native Exec Bridge toggle
+ log viewer in Settings (`kodrix_exec.log`).

---

## 4. Status / what's unverified

**2026-09-27, next steps in order:**
1. The owner tests the **Phase A experimental editor** from CI run 36231296866
   (artifact `kodrix-debug-apk-arm64-v8a`): switch off = unchanged; switch on = highlighting,
   typing/auto-indent/auto-close, completion popup, save + modified dot, error squiggles, split
   view, tab switching. If it's good, merge the branch to `main` via PR, then start Phase B.
2. Runtime tests below (Rust first; the owner postponed it because it's a big download on
   mobile data).

Facts: latest published release is **v1.1.2** (2026-06-18); `versionName` 1.2.0 in
`androidApp/build.gradle.kts` is unreleased. minSdk 28, targetSdk 34, compileSdk 34 (the README
still says "Android 10+ / API 29"). Update SECURITY.md's supported-versions table when 1.2.0
ships.

Watch item: npm is configured to use `usr/bin/sh` as its shell (`NPM_CONFIG_SHELL`), which is
itself a script in app storage. `npm install` works; if `npm run <script>` fails with
"Permission denied", that's the cause.

CI housekeeping (not done): the workflow warns that Node 20 actions are deprecated and
`actions/setup-java@v4` should move to v5.

Repo hygiene flagged (not acted on, owner to decide): `kodrix.jks` (a signing keystore) and
two `google-services.json` files are committed to the public repo. If `kodrix.jks` signs
release APKs, rotate it and purge it from history. `scratch/` (>1,000 extracted Termux files)
is committed despite being in `.gitignore`. `TerminalViewModel.kt` is ~3.9k lines doing
almost everything, and there are no tests.

Confirmed by owner on device: terminal `node`/`npm`/`git` work and the language servers
install (2026-09-26, `main` builds). From an older build: Rust installed from Runtimes;
`rustup`/`rust` "not found" (expected, now explained); `ls` Permission denied (fixed since).

**Not yet verified on a device** (all compile in CI):
1. Downloaded Node: switch to Latest → `node -v` shows it (depends on linker64 launch + the
   wrapper-overwrite fix).
2. Python install → `python3 --version`, `pip`, Pyright autocomplete in `.py`.
3. `rustc --version`, `cargo new hello && cd hello && cargo run`, rust-analyzer autocomplete.
4. "All packages" install, e.g. `sqlite` → `sqlite3`.
5. Pause/Resume; startup time; keyboard behaviour; browser camera/mic card.

If something fails: tap the red error on the runtime card; Settings → Native Exec Bridge →
View log (`files/kodrix_exec.log`); `adb logcat -s BinaryManager VersionChecker Kodrix`.

## 5. Known limits / next ideas
- Go/Zig builds likely blocked (raw syscalls). Would need a seccomp/ptrace approach or
  static re-linking; not attempted.
- Ruby, Java, Kotlin, Perl, Elixir, Haskell, Nim, Crystal: run in terminal, no Android LSP yet.
- 32-bit/x86 phones lack a few catalog languages (hidden automatically).
- Icons referenced by the registry (`KodrixMarketplace/icons/*.png`) don't exist yet.
- `documents/CURRENT_TASK.md` is older (June 2026) and not updated by this session.
