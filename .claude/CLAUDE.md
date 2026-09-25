# Kodrix IDE

Android IDE (Kotlin Multiplatform + Compose) with a built-in terminal, editor with LSP
autocomplete, and installable language runtimes.

**Mission:** one Android app where anyone can code in many languages with zero setup —
install the app, pick a language, code. A language only counts as supported when it both
runs *and* has working autocomplete (LSP). Runtimes and their updates must never need an
app update. Full mission: `documents/HANDOFF.md` §0.

Start with `documents/HANDOFF.md` (current state, branches, what's untested) and
`documents/LANGUAGE_PACKS.md` (runtime system design and registry schema).

## Working rules (from the owner)
- Work on branch `claude/confident-rubin-3wc23a` (both KodrixIDE and KodrixMarketplace);
  the owner merges to `main`.
- Before every commit + push, ask who gets credit — Only me (author
  `Zohaib Baig <zohaibbaig144@gmail.com>`, no Claude line) / Me + Claude (same author plus a
  `Co-Authored-By: Claude` line) / Only Claude — and don't push until answered.
- The owner tests on a Samsung, Android 14 (arm64-v8a); CI builds per-ABI APK artifacts.
- The owner moves between a Windows PC, new chat sessions, and Termux + proot on their phone.
  Only the repo travels. When they say **"push for the session"** (or say they're switching
  device or chat, or going to Termux/proot), use the `push-session` skill
  (`.claude/skills/push-session/SKILL.md`). It saves every instruction, rule, piece of project
  info and the current state into this file and `documents/HANDOFF.md`, then commits and
  pushes. Rules the owner gives in any session apply to all later sessions.
- The older docs (`documents/CURRENT_TASK.md`, `CLINE_HANDOFF.md`, `CODEBASE.md`,
  `planning/`) predate the Kotlin Multiplatform layout and use old `app/` paths. Trust
  HANDOFF.md and LANGUAGE_PACKS.md over them.

## Layout
- `shared/src/androidMain/kotlin/com/kodrix/zohaib/` — app code
  - `viewmodel/TerminalViewModel.kt` — main state, terminals, LSP startup
  - `bridge/BinaryManager.kt` — runtimes: registry, install, activate, pause, search
  - `runtime/` — `TermuxRepo` (index/resolve/.deb), `RuntimeExec` (linker64 launch),
    `RuntimeManifest`, `BuiltinCatalog`
  - `ui/` — Compose screens (`IDEView`, `MarketplaceView`, `BrowserView`, …)
- `androidApp/src/main/cpp/kodrix_exec.c` — LD_PRELOAD exec/path shim
- `.github/workflows/android-apk.yml` — APK build
