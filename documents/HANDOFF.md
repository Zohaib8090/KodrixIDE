# Kodrix — Handoff (cloud → local)

Last updated: 2026-09-25. Written at the end of a long cloud session so work can continue
locally. Read this first; details live in the linked docs.

---

## 1. Where the code is

| Repo | Branch with the new work | Merged to `main`? |
|---|---|---|
| `Zohaib8090/KodrixIDE` | `claude/confident-rubin-3wc23a` | **No** — 14 commits ahead of `main` |
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
- Keep working on `claude/confident-rubin-3wc23a` in both repos; the owner merges to `main`.
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

---

## 3. What was built (newest first)

### 3.1 UX fixes (latest, **not yet tested on device**)
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

Confirmed by owner on device (older build): Rust installed from Runtimes; `rustup`/`rust`
"not found" (expected — now explained); `ls` Permission denied (fixed since).

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
