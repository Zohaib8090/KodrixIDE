# Language Packs — Design (v2)

**Status:** Draft for implementation. Phase 0 (spike) must pass before any other work starts.
**Audience:** the engineer/agent implementing this. Sections are written so each phase can be executed independently once its predecessor's acceptance criteria are met.

> **Verified against the live Termux index on 2026-09-24.** Facts marked **[verified]** were
> checked against `packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages`
> (3,002 packages) and the actual `clang_21.1.8-3_aarch64.deb`.

## 0. Goal

Adding a programming language to Kodrix = pushing a JSON manifest to `KodrixMarketplace`. No new APK.

User flow: open **Languages** panel → pick a language → tick optional parts (language server, extra tools) → **Install** → highlighting, LSP and Run work immediately → the pack keeps itself updated.

**Toolchain source (decided):** Termux packages only. Kodrix downloads the prebuilt `.deb` files Termux publishes and unpacks them into its own storage. The Termux app is never required or visible to the user.

### Non-goals (v1)
- Debuggers (DAP), formatters beyond what the LSP offers, notebook kernels.
- Languages not available as Termux packages.
- Running on Android < 10 differently from Android 10+ (same code path; see Phase 0).
- User-authored packs from arbitrary URLs (only the official marketplace repo in v1).

---

## 1. Current state (unchanged facts)

Each language is hand-wired in five places:

| Concern | Where | What's hardcoded |
|---|---|---|
| Extension → language | `TerminalViewModel.kt:506` `getLanguageId()` | `"py" -> "python"`, `"cpp" -> "cpp"`, … |
| Syntax highlighting | `SyntaxHighlighter.kt:17` | `when (extension)` → built-in keyword lists |
| LSP launch | `TerminalViewModel.kt:800–874` `startNativeLsp()` | one `when (langId)` branch per language |
| Installer | `TerminalViewModel.kt:1037` `installCppToolchain()`; `:3819` `installPythonInBackground()`; `:1312` `installPylspIfNeeded()` | bespoke function per language |
| Names, run commands | `TerminalViewModel.kt:894`, run panel | per-language strings |

The existing marketplace (`MARKETPLACE_ARCHITECTURE.md`, `marketplace/python-support/manifest.json`) only carries metadata (name, description, icon). No behaviour is data-driven.

---

## 2. Hard constraints

### A. Android will not `execve()` downloaded binaries (W^X) — the gating constraint

`androidApp/build.gradle.kts` has `targetSdk = 34`. On API 29+ with targetSdk > 28, SELinux denies `execute_no_trans` on files in the app's data dir. Only binaries inside the APK (`nativeLibraryDir`, e.g. `libnode_bin.so`, `libgit_bin.so`) can be exec'd. Downloaded Termux binaries live in `filesDir`.

Evidence in the repo:
- `WrapperManager.kt:145` and `TerminalViewModel.kt:833` exec downloaded binaries directly (`exec "<filesDir>/versions/…/clangd"`).
- `TerminalViewModel.kt:1184` skips the clang version check because clang "cannot self-execute inside the Android app sandbox" — this is the restriction, and the skip masks it.
- `native-lib.cpp` has the standard workaround (run via `/system/bin/linker64 <binary> args…`; the linker `mmap`s the file, which SELinux allows) but only for `node` (`:263`), and the hooks are active only "when LD_PRELOADed" (`:83`). **Nothing sets `LD_PRELOAD`. The hooks are dormant.**

**Consequence:** the current on-demand C/C++ toolchain probably does not run on Android 10+ today. Unconfirmed — confirm in Phase 0.

**Why wrapper scripts are not enough:** a `#!/system/bin/sh` wrapper still needs the *kernel* to exec the script file itself, which SELinux denies for the same reason. Any child process (clang → `ld.lld`, pip → python, `make` → `cc`) calls `execve` on a path in `filesDir`. So the fix must intercept exec in every process, or avoid the restriction entirely (options in §5).

### B. Termux binaries hardcode Termux's prefix

Prefix is `/data/data/com.termux/files/usr` (31 chars up to `usr`). It is baked into RUNPATH, shebangs, `python` sysconfig, clang's default include/resource/sysroot paths, pip-generated scripts, and text config. Kodrix's prefix (`/data/data/com.kodrix.zohaib/files/…`) is longer, so binary in-place patching is not generally possible.

Approach, in order of preference:
1. **Runtime path redirect** in the preload library (`open/openat/stat/lstat/access/readlink/opendir/execve/realpath…` mapping `/data/data/com.termux/files` → Kodrix files dir). `native-lib.cpp:182` already does part of this.
   **Redirect target:** with per-pack envs (§5.1) there is no single Kodrix prefix. The redirect must map to the env of the binary that is running. Record it at exec-rewrite time (an env var set by ExecProxy alongside the `/proc/self/exe` fix, §2.F), not a global constant.
2. **Environment** to avoid patching: `LD_LIBRARY_PATH` (searched before RUNPATH), `PYTHONHOME`, `SSL_CERT_FILE`, `TMPDIR`, `HOME`, `PATH`.
3. **Install-time text rewrite** only for shebangs and small text config files (safe, length doesn't matter in text).
4. Do **not** rely on `patch_elf.js`. Its `$ORIGIN/./././…` padding is wrong for executables in `usr/bin/` (needs `$ORIGIN/../lib`). Keep it only as a fallback experiment.

### C. Pinned Termux URLs break — **already broken [verified]**

`installCppToolchain()` hardcodes 7 exact URLs (`clang_21.1.8-2_…deb`, …). Termux's main repo serves essentially only current builds.

**As of 2026-09-24, 6 of the 7 URLs return HTTP 404** (Termux moved to `clang 21.1.8-3`; only `zstd_1.5.7-1` still resolves). **C/C++ install is currently broken for every user**, independent of constraint A. The hand list was also incomplete: Termux's `clang` depends on `lld`, `llvm` and `libcompiler-rt`, none of which were downloaded, so linking could never have worked.

Packages must be resolved from the repo's `Packages` index at install time.

### D. Dependencies are listed by hand

The `Packages` index declares `Depends:` for everything. A resolver replaces all hand-written lists.

**Measured closures [verified, aarch64]:**

| Roots | Packages | Download | Installed |
|---|---|---|---|
| `python`, `python-pip` | 18 | 11 MB | 53 MB |
| `clang`, `ndk-sysroot` | 15 | 87 MB | 518 MB |

`rust` (125 MB alone) and `golang` both depend on `clang`, so large dependencies are shared across packs. The content-addressed store (§5.1) must dedupe them.

### E. Python is self-hosted today

Python 3.13.13 is a zip on `KodrixMarketplace` releases (`TerminalViewModel.kt:3822`). With the Termux-only decision it moves to Termux's `python` + `python-pip` (see Phase 6). Termux currently ships **Python 3.14.6 [verified]**.

### F. Other exec-adjacent gotchas (new)
- Shebang scripts go through kernel `execve` → blocked; the hook must parse the shebang and re-dispatch through `linker64 <interpreter> <script>`.
- Under `linker64`, `/proc/self/exe` points at the linker, not the binary. Python, clang, and lld use it to locate their prefix/resource dir. The hook must set/emulate this (termux-exec does the same via extra env vars; study it). Concretely: when rewriting an exec, put the real binary path in an env var and hook `readlink`/`readlinkat` on `/proc/self/exe` to return it.
- bionic's `execvp`/`posix_spawn` may call `execve` internally without going through the PLT, so hook **all** of: `execve, execv, execvp, execvpe, fexecve, posix_spawn, posix_spawnp`. LLVM tools (clang → `ld.lld`) spawn children via `posix_spawn`. Statically linked binaries (e.g. Go) bypass `LD_PRELOAD` entirely, **and cannot be launched through `linker64` either** (the linker only loads dynamic executables); note as a known limitation.
- Downloaded `.so` files can be `dlopen`ed/`mmap`ed executable from `filesDir` (only `execute_no_trans` is denied), so libraries are fine.
- Use `/system/bin/linker64` on 64-bit ABIs, `/system/bin/linker` on 32-bit.

---

## 3. Architecture overview

```
Marketplace JSON (pack manifest)
        │
        ▼
 PackManager ──► PackageResolver ──► TermuxRepoClient (Packages index, .deb download, SHA256)
        │                                   │
        │                                   ▼
        │                          DebExtractor → versioned dir
        ▼
 Runtime (ExecProxy preload lib + env builder + path redirect)
        │
        ├── HighlightService (grammar by ext)
        ├── LspService  (launch template → process)
        └── RunService  (run/build templates → terminal)
```

Everything language-specific is data in the manifest; Kotlin knows nothing about "python" or "cpp".

Suggested module boundaries (Kotlin, shared where possible; Android-only for exec):
- `packs/PackManifest.kt` — schema + validation
- `packs/TermuxRepoClient.kt` — index fetch/parse, mirrors
- `packs/PackageResolver.kt` — dependency closure
- `packs/DebExtractor.kt` — `ar` → `data.tar.xz` → files/symlinks
- `packs/PackInstaller.kt` — orchestration, atomic switch, rollback, GC
- `runtime/ExecProxy` (C++, extend `native-lib.cpp`) + `runtime/EnvBuilder.kt`
- `ui/LanguagesPanel.kt`

---

## 4. Phase 0 — Feasibility spike (GATE)

Goal: prove a downloaded Termux binary can run on a real device with `targetSdk = 34`. **Do not start Phase 1 until this passes.** Cloud sessions cannot do this; it needs a real device (Android 14, arm64) — an emulator is a secondary check only.

**Test device (decided):** the owner's Samsung phone, Android 14.

Test matrix:

| # | Test | Pass |
|---|---|---|
| 1 | Download + extract Termux `python` and deps manually; run `/system/bin/linker64 <prefix>/bin/python3.14 -c "print(1)"` | prints `1` |
| 2 | Same for `clang --version` | prints version |
| 3 | Compile+link+run hello world (`clang` → `ld.lld` child exec) | binary runs |
| 4 | `python3 -c "import subprocess; subprocess.run(['<prefix>/bin/python3','-c','print(2)'])"` (child exec) | prints `2` |
| 5 | A `#!` script in `<prefix>/bin` executes | runs |
| 6 | `pip --version`, `python -c "import sysconfig; print(sysconfig.get_paths())"` show Kodrix paths or redirect correctly | no `com.termux` leakage that breaks behaviour |

Also run a **baseline** of tests 1–2 with a plain `execve` (no linker, no preload) to confirm constraint A on the device rather than assume it.

Steps: (a) wire `LD_PRELOAD` (from `nativeLibraryDir`) into the terminal/LSP process env, (b) generalise the exec hook from `node`-only to "any ELF under a pack prefix", (c) add shebang handling and the `/proc/self/exe` fix, (d) rerun the matrix.

**Implementation status:** (a)–(c) exist as `androidApp/src/main/cpp/kodrix_exec.c`
(`libkodrix_exec.so`), covering `execve`/`execv`/`execvp`/`execvpe`,
`posix_spawn`/`posix_spawnp`, the path-redirect hooks, and the shebang/`/proc/self/exe`
handling described in §2.A/§2.B/§2.F. Verified on host (glibc, not bionic/NDK — this session
had no Android device or NDK toolchain): compiles and links cleanly, and doesn't break plain
commands, nested `sh -c`, PATH search, or Python's `subprocess` (which uses `posix_spawn`
internally); real failures (`EACCES`) log correctly while routine `ENOENT` PATH-search misses
are suppressed so the log stays readable. **This is not (d) — the actual matrix has not run on
a device.**

**How to run (d) yourself, using Kodrix's own terminal — no separate Termux install, no
`TermuxPackageManager` needed yet:**
1. Build and install this branch's APK, open Settings → Developer, turn on **Beta Mode**,
   then turn on **Native Exec Bridge**.
2. Open a new terminal tab (the toggle only takes effect for new sessions).
3. Download a Termux package's `.deb` and extract it into `$KODRIX_ROOT`
   (`filesDir/kodrix-lang-root`, already on `PATH`-adjacent and pre-created), e.g.:
   ```
   curl -O https://packages-cf.termux.dev/apt/termux-main/pool/main/p/python/python_3.14.6-1_aarch64.deb
   ar x python_3.14.6-1_aarch64.deb
   tar --strip-components=5 -xf data.tar.xz -C "$KODRIX_ROOT"   # strips ./data/data/com.termux/files/
   ```
4. Run the extracted binary directly: `$KODRIX_ROOT/usr/bin/python3.14 -c "print(1)"` (test #1).
   No manual `linker64`/`LD_PRELOAD` invocation needed — the shim intercepts the `execve`
   automatically once it's preloaded into the shell.
5. Work through the rest of the matrix (`clang`, child excs, shebang scripts, `pip`/`sysconfig`
   path leakage) the same way.
6. Settings → Developer → **View Exec Log** shows every rewrite decision and any real failure
   (`kodrix_exec.log` in `filesDir`), without needing `adb logcat`.
7. Record results (device model, Android version, per-test pass/fail, log excerpts for
   failures) in a `SPIKE_RESULTS.md` as this section originally specified.

If a test fails, the log is the first thing to check — and if the log itself is empty, that
means `LD_PRELOAD` never took effect (check the toggle actually applied to a *new* terminal
session, not one already open).

**Fallbacks if it fails or proves too leaky — try in this order (decided, since distribution is GitHub/F-Droid only, §12):**
1. `targetSdk = 28` — direct `execve` works again (this is what Termux itself does). Cheapest option, and now allowed because Play Store is out of scope. Downsides: Android may raise the minimum installable targetSdk in future, and it forfeits some newer platform behaviours. If chosen, ExecProxy is still needed for path redirect (constraint B) but no longer for exec interception.
2. `proot`-style ptrace exec interception — robust and handles static binaries, but slower.
3. Rebuild the needed Termux packages with Kodrix's own prefix via `termux-packages` in GitHub Actions and host the repo. Eliminates constraint B, but breaks "upstream Termux only" and adds a maintained build pipeline.

Phase 0 should test the ExecProxy approach first (it keeps `targetSdk = 34`), and fall through this list only on failure.

Deliverable: a short `SPIKE_RESULTS.md` with the matrix results per device/Android version and the chosen path.

---

## 5. Phase 1 — Runtime layer

### 5.1 On-disk layout

```
<filesDir>/packs/
  store/<pkgname>/<version>/…        # extracted package contents (immutable)
  envs/<pack-id>/<pack-version>/     # merged prefix for a pack (symlink farm or copy)
  envs/<pack-id>/current -> …        # atomic switch
  lock/<pack-id>.lock.json           # resolved names+versions+sha256
  cache/debs/                        # downloaded .debs (evictable)
  index/<arch>/Packages(.json)       # cached repo index + timestamp
```

Rationale: a versioned, immutable store + a switchable `current` link gives atomic install/upgrade and one-step rollback.

**Writable areas:** some components install files after extraction (e.g. `pip install` for the Python LSP, §7). Those writes must not land in the immutable `store/` through a symlink. Give each env a real, writable overlay directory (e.g. `envs/<pack>/<ver>/local/`, used via `pip install --prefix ${prefix}/local` or `PYTHONUSERBASE`), and include it in `PATH`/`PYTHONPATH` via EnvBuilder.

### 5.2 ExecProxy (native)
- Built into `libkodrix_exec.so`, shipped in `jniLibs/`, set via `LD_PRELOAD` for **every** process Kodrix spawns (terminal, LSP, run, build). Preloaded value is inherited by children through the environment; the hook must re-inject it if a child clears env.
- Behaviour on exec of path `P`:
  1. Apply path redirect (`com.termux` → Kodrix prefix).
  2. If `P` is inside `<filesDir>` and is an ELF → `execve(linker, [linker, P, args…], env)`.
  3. If `P` starts with `#!` → parse interpreter (+ one optional arg), rewrite it via redirect, recurse.
  4. Otherwise pass through.
- Set `/proc/self/exe`-dependent env vars the packages need (per the Phase 0 findings).
- Must be async-signal-safe between `fork` and `exec` (no malloc in the hook's fast path where avoidable).

### 5.3 EnvBuilder
Produces the env for any process from the manifest + resolved prefix: `PATH`, `LD_LIBRARY_PATH`, `LD_PRELOAD`, `HOME`, `TMPDIR`, `PREFIX`, `SSL_CERT_FILE`, plus pack-specific `env` from the manifest (template-expanded). Single source of truth; replaces per-language env code in `startNativeLsp()`.

---

## 6. Phase 2 — Package manager

### 6.1 Repo client
- Source: Termux main repo `Packages` index for the device ABI (`aarch64`, `arm`, `x86_64`, `i686`), plus a configured mirror list with fallback. Fetch on install and refresh at most every 24 h; cache locally. (The aarch64 index is ~1.9 MB uncompressed [verified].)
- Parse fields: `Package, Version, Architecture, Depends, Pre-Depends, Provides, Filename, Size, SHA256`. `Architecture: all` packages (e.g. `python-pip`, `rust-src`) appear in each per-arch index [verified].
- **Integrity:** verify each `.deb` against the `SHA256` in the index. Verify the index against `Release`/`InRelease` and Termux's signing key if practical; if not in v1, document the gap and rely on HTTPS + hash chain.

### 6.2 Resolver
- Input: list of package names (from the manifest). Output: ordered install closure.
- Must handle: alternatives (`a | b` → pick first available), virtual packages (`Provides:`), version constraints (`(>= x)`), cycles, and packages already provided by Android (skip a small deny-list, e.g. `termux-tools`, `termux-exec`, `termux-keyring`, anything requiring `apt`/`dpkg` state).
- Deterministic: same index → same closure. Log the closure.

### 6.3 Extractor
- Parse `ar` → `data.tar.xz` (use a vetted library for XZ, e.g. Commons Compress). Note that `ar` member names carry a trailing `/` (`data.tar.xz/`) [verified]; the existing `startsWith("data.tar")` check handles it.
- Paths inside `data.tar` are `./data/data/com.termux/files/usr/…` [verified]; strip to `usr/…`.
- Preserve symlinks (real tar symlinks; e.g. `bin/clang -> clang-21` [verified]; handle legacy `SYMLINKS.txt` if present) and modes.
- Reject path traversal (`..`, absolute paths escaping the store), cap decompressed size.
- Textual rewrite pass (shebangs, small config) per §2.B.3.

### 6.4 Installer
1. Resolve closure → download with resume + progress → verify SHA256.
2. Extract each package into `store/<name>/<version>/` (skip if present).
3. Build `envs/<pack>/<ver>/` (symlink farm over store dirs).
4. **Run the pack's `verify` command** (§7) using the real runtime. Only on success, atomically repoint `current` and write the lockfile. On failure, keep the previous `current`.
5. GC: remove store entries not referenced by any lockfile, keep the previous version for one rollback.

The existing "skip version check to avoid rolling back" hack (`TerminalViewModel.kt:1184`) must be deleted; a failing `verify` is a real failure.

---

## 7. Phase 3 — Manifest schema

Packs list Termux **package names only**; versions are resolved locally and stored in the lockfile. Packs contain **no absolute paths** — only template variables.

**Template variables:** `${prefix}` (pack env root), `${bin}`, `${lib}`, `${file}`, `${fileDir}`, `${fileStem}`, `${workspace}`, `${home}`, `${tmp}`.

A component may also have **`postInstall`**: argv arrays run once after the component's packages are installed, writing only into the env's writable overlay (§5.1). This is required because some language servers are not Termux packages (see the Python example).

```jsonc
{
  "schema": 1,
  "id": "python",
  "name": "Python",
  "version": "1.0.0",            // pack (manifest) version, not toolchain version
  "icon": "python.svg",
  "description": "Python 3 with pip",
  "minKodrixVersion": "1.2.0",

  "languages": [
    {
      "id": "python",
      "extensions": ["py", "pyw"],
      "filenames": [],
      "shebangs": ["python", "python3"],
      "highlight": { "type": "textmate", "grammar": "grammars/python.tmLanguage.json" },
      "comment": { "line": "#", "block": null }
    }
  ],

  "components": [
    { "id": "runtime", "required": true,  "termuxPackages": ["python", "python-pip"] },
    // python-lsp-server is NOT a Termux package [verified] → install via pip.
    // jedi-language-server is suggested over pylsp: pylsp depends on ujson (a C extension
    // with no Android wheel), so pip would need a C compiler, i.e. the whole cpp pack.
    { "id": "lsp",     "required": false, "default": true,  "termuxPackages": [],
      "postInstall": [["${bin}/python3", "-m", "pip", "install", "--prefix", "${prefix}/local", "jedi-language-server"]] },
    { "id": "tools",   "required": false, "default": false, "termuxPackages": ["ruff"] }
  ],

  "env": { "PYTHONHOME": "${prefix}", "PYTHONDONTWRITEBYTECODE": "1" },

  "verify": { "cmd": ["${bin}/python3", "-c", "print('ok')"], "expect": "ok", "timeoutSec": 20 },

  "lsp": {
    "requiresComponent": "lsp",
    "languageIds": ["python"],
    "cmd": ["${prefix}/local/bin/jedi-language-server"],
    "transport": "stdio",
    "initializationOptions": {}
  },

  "run": [
    { "id": "run", "label": "Run", "cmd": ["${bin}/python3", "${file}"], "cwd": "${fileDir}" }
  ]
}
```

C/C++ example differences: components use the `clang` package, which **also contains `clangd`** (there is no separate `clangd` package [verified]) plus `ndk-sysroot`; `run` has a two-step `build` (`clang++ ${file} -o ${tmp}/${fileStem}`) then `run` (`${tmp}/${fileStem}`); `lsp.cmd` is `${bin}/clangd` with `--compile-commands-dir`/flags templates.

Other packages confirmed to exist for later packs [verified]: `rust` (depends on `clang`), `rust-analyzer` (depends on `rust-src`), `golang` (depends on `clang`), `ruff`, `make`, `cmake`, `openjdk-21`.

Validation rules (reject the pack, don't guess): unknown `schema`; component ids unique; every `lsp.requiresComponent` exists; only whitelisted variables used; no `/` absolute path literals in `cmd`/`env`/`postInstall`; `termuxPackages` names match `[a-z0-9+.-]+`.

**Ownership model:** a language can be provided by exactly one installed pack at a time; extension conflicts between packs are resolved by explicit user choice, not load order.

---

## 8. Phase 4 — Generic editor integration

Replace the hardcoded `when` blocks with lookups against the installed-pack registry:

- `getLanguageId()` (`TerminalViewModel.kt:506`) → registry lookup by extension/filename/shebang.
- Highlighting (`SyntaxHighlighter.kt:17`) → **TextMate grammars** (or tree-sitter), not keyword lists; otherwise "no APK change" fails for any language whose syntax isn't already built in. Keep the current keyword highlighter as the fallback when a grammar is missing/invalid. Grammars ship in the pack (JSON) — no native code.
- `startNativeLsp()` (`:800–874`) → one generic launcher: expand `lsp.cmd` templates, build env via EnvBuilder, spawn through ExecProxy, speak stdio LSP. Reconnect/backoff and per-workspace instance policy live here once, not per language.
- Run panel → render `run[]` entries; build-then-run chains supported.
- Friendly names (`:894`) → manifest `name`.

Keep a small built-in "core" set (plain text, JSON, Markdown, etc.) in the APK so the editor works with zero packs installed.

---

## 9. Phase 5 — Languages panel UI

- Side panel **Languages**: list from marketplace (name, icon, description, size estimate, installed version/update badge).
- Detail sheet: components as checkboxes (required ones locked on), computed download size (from resolver), and an explicit **"This will run:"** list (LSP command, run commands) before install — see §10.
- States: `Not installed → Resolving → Downloading (per-package progress) → Extracting → Verifying → Installed`; and `Update available`, `Failed (retry / view log)`.
- Actions: Install, Update, Repair (re-verify + reinstall broken packages), Uninstall (remove env + unreferenced store entries), Roll back.
- Install runs as a foreground service (survives app backgrounding); resumable after process death; Wi-Fi-only toggle for large packs.
- Offline: installed packs work fully offline; index cache is used for "update available" checks when possible.
- Persist install state + lockfiles; a corrupted state must degrade to "Repair", never crash the editor.

---

## 10. Security

A manifest defines commands that Kodrix executes → treat as a remote-code-execution surface by design.

- Manifests come only from the official `KodrixMarketplace` repo over HTTPS; **sign manifests** with Ed25519 and reject unsigned/invalid ones. (Android's built-in Ed25519 is API 33+ only and `minSdk = 28`, so verification needs a library such as BouncyCastle or Tink.)
- **Key custody (decided defaults):** the project owner holds the private key offline (not in the repo, not in CI secrets). Signing happens locally via a small script that signs every manifest and writes a detached `.sig` next to it. The APK embeds a **list** of trusted public keys (not one), so a key can be rotated by shipping a new APK that adds the new key before the old one is retired. If the key is lost or compromised: release a new APK with a fresh key and revoke the old one.
- Show the user exactly which commands a pack will run before first install and on any update that changes `cmd`/`env`/`termuxPackages`/`postInstall`.
- All downloaded `.deb`s hash-verified (§6.1). No unauthenticated HTTP anywhere.
- Extraction hardened against path traversal, symlink escape, zip/tar bombs.
- Packs cannot reference paths outside `${prefix}`, `${workspace}`, `${tmp}`, `${home}`.
- Fix the already-noted committed keystore (`kodrix.jks`) separately; do not reuse any signing material in this system.

---

## 11. Updates

- Termux serves only current builds, so partial upgrades (e.g. new `libllvm` with old `clang`) break ABI. `clang` pins `libllvm (= 21.1.8-3)` exactly [verified]. **Always upgrade a pack's full dependency closure together** into a new `envs/<pack>/<ver>/`, verify, then atomically switch; keep the previous one for rollback.
- Two independent version tracks: manifest version (behaviour) and toolchain versions (from the index, recorded in the lockfile).
- Update checks: on app start (throttled to 24 h) and manually from the panel. Never auto-update in the middle of a running LSP/run session; apply on next start or on user confirmation.
- Manifest updates that change commands re-trigger the §10 consent prompt.

---

## 12. Distribution

**Decided:** Kodrix ships via **GitHub Releases and F-Droid only**. Google Play is out of scope, because downloading and executing native code from outside the store is against its policy. This keeps the `targetSdk = 28` fallback (§4) available.

Notes:
- GitHub Releases is the primary channel. F-Droid builds from source and may flag runtime code download as an anti-feature; check its inclusion policy before submitting, and keep the language-pack system separable enough that an F-Droid build can be reviewed on its own.
- The APK must not bundle any pack toolchains; packs are only fetched at runtime by explicit user action.

---

## 13. Migration of existing languages

1. **C/C++ first (Phase 6a):** replace `installCppToolchain()` with the `cpp` pack (`clang` package, which includes `clangd`; resolver-driven). Delete the hardcoded URL list. Since that list already 404s (§2.C), there are no working C/C++ installs to preserve.
2. **Python (Phase 6b):** move from the self-hosted zip (3.13.13) to Termux `python` (3.14.x) + `python-pip`. This is the hardest real test — native extensions, pip-generated scripts, sysconfig paths. `installPythonInBackground()` and `installPylspIfNeeded()` go away. Users' pip-installed packages built for 3.13 won't carry over to 3.14; the migration prompt must say so.
3. Existing users: detect old installs, offer one-click migration, keep the old install until the new pack verifies, then remove it. Never leave a user without a working Python mid-migration.
4. Then Node/Git: they already ship in the APK (`libnode_bin.so`, `libgit_bin.so`). Leave them as-is; optionally expose them as built-in (non-installable) packs so the panel is consistent.

---

## 14. Testing

- **Unit (JVM):** manifest validation, template expansion, resolver (alternatives, virtuals, constraints, cycles) against saved real `Packages` snapshots, deb extraction incl. symlinks and traversal attempts.
- **Instrumented (device):** the Phase 0 matrix as an automated suite; install → verify → run → uninstall for `python` and `cpp`; upgrade + rollback; kill process mid-install and resume.
- **Contract test in CI:** nightly job that fetches the live Termux index, resolves every published pack, and downloads/hash-checks the closure — catches upstream breakage (constraint C) before users do. This would have caught the current 404s.
- **Manual matrix:** Android 10, 13, 14, 15; arm64 device + x86_64 emulator; low-storage; airplane mode mid-install.

---

## 15. Phases and acceptance criteria

| Phase | Scope | Done when |
|---|---|---|
| 0 | Spike (§4) | Matrix passes on a real Android 14 device, or a fallback is chosen and documented |
| 1 | Runtime: ExecProxy, EnvBuilder, path redirect | `python3`/`clang` run from `filesDir` incl. children + shebangs, in the app terminal |
| 2 | Package manager (client, resolver, extractor, installer) | Installs a closure from the live index; atomic switch + rollback work; failed `verify` never replaces `current` |
| 3 | Manifest schema + signing + validation | Two hand-written manifests (`cpp`, `python`) validate; unsigned/invalid rejected |
| 4 | Generic editor integration | Highlighting, LSP, Run all driven by manifests; no per-language `when` left in Kotlin |
| 5 | Languages panel UI | Full install/update/repair/uninstall flow with progress and consent screen |
| 6a/6b | Migrate C/C++, then Python | Old installers deleted; existing users migrated without downtime |
| 7 | Third language via JSON only (e.g. Go or Rust) | Added with **zero** app changes — proves the goal |

Phase 7 is the real acceptance test of the whole design.

---

## 16. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Exec interception leaks (statically linked binaries, odd `execve` paths) | some tools fail | Phase 0 matrix; per-pack `verify`; proot fallback |
| `com.termux` path leaks into behaviour | subtle breakage | redirect layer + env; grow the test matrix per package |
| Upstream Termux churn | installs break (already happened, §2.C) | resolver + nightly contract test + mirror list |
| Android tightens further | feature dies | keep `targetSdk = 28` and self-built-prefix fallbacks documented |
| Storage use (C/C++ alone is ~518 MB installed [verified]) | user friction | show sizes up front, uninstall + GC, cache eviction |
| Malicious/compromised manifest | RCE | signing + consent prompt + hash-pinned packages |

---

## 17. Decisions log

| # | Decision | Status |
|---|---|---|
| 1 | Distribution: GitHub Releases + F-Droid only, no Play Store | **Decided** (§12) |
| 2 | Phase 0 failure path: `targetSdk = 28` → proot → self-built prefix | **Decided** (§4) |
| 3 | Highlighting: TextMate grammars (JSON-only, no native code) | **Decided** (§8) |
| 4 | Manifest signing: Ed25519, owner-held offline key, multi-key list in APK | **Decided** (§10) |
| 5 | Should `node`/`git` appear as built-in entries in the Languages panel? | Open — default: yes, non-installable, for UI consistency |
| 6 | Minimum Android version for language packs | Open — default: Android 10 (API 29) |
| 7 | Phase 0 test device | **Decided:** owner's Samsung, Android 14 |
| 8 | Python LSP: `jedi-language-server` (pure Python) vs `pylsp` (needs a C compiler for `ujson`) | Open — default: `jedi-language-server` |
