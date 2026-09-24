# Language Packs — Design

**Status:** Draft for review. No code written yet.
**Goal:** Adding a programming language to Kodrix should mean pushing a JSON file to
`KodrixMarketplace`, not shipping a new APK. The user opens a **Languages** side panel,
taps **Install** on a language, and can immediately edit (highlighting + LSP) and run code in it.
**Toolchain source (decided):** Termux packages only.

---

## 1. How languages work today

Each language is hand-wired into Kotlin in five places:

| Concern | Where | What's hardcoded |
|---|---|---|
| Extension → language | `TerminalViewModel.kt:506` `getLanguageId()` | `"py" -> "python"`, `"cpp" -> "cpp"`, … |
| Syntax highlighting | `SyntaxHighlighter.kt:17` | `when (extension)` → built-in keyword lists |
| Language server launch | `TerminalViewModel.kt:800–874` `startNativeLsp()` | one `when (langId)` branch per language, each building its own shell command and env |
| Installer | `TerminalViewModel.kt:1037` `installCppToolchain()`; `:3819` `installPythonInBackground()`; `:1312` `installPylspIfNeeded()` | a bespoke function per language |
| Friendly names, run commands | `TerminalViewModel.kt:894`, run panel | per-language strings |

The existing marketplace (`MARKETPLACE_ARCHITECTURE.md`, `marketplace/python-support/manifest.json`)
only describes *metadata* (name, description, icon). None of the behaviour above is data-driven,
which is why adding a language currently requires reprogramming the app.

---

## 2. Hard constraints found during investigation

These decide the design. **Constraint A is the big one.**

### A. Android won't execute binaries the app downloads (W^X)

`androidApp/build.gradle.kts` sets `targetSdk = 34`. Since Android 10, apps targeting API 29+
cannot `execve()` files in their own writable data directory — SELinux denies it. Only binaries
shipped **inside the APK** (extracted to `nativeLibraryDir`) are executable. That's why Node and
Git ship as `libnode_bin.so` / `libgit_bin.so` in `jniLibs/`.

Termux packages are *downloaded*, so they live in `filesDir` and hit this wall. What the code does today:

- `WrapperManager.kt:145` and `TerminalViewModel.kt:833` launch downloaded binaries directly
  (`exec "<filesDir>/versions/…/clangd"`).
- `TerminalViewModel.kt:1184` notes that Clang "cannot self-execute inside the Android app
  sandbox", so the install skips the version check to avoid rolling itself back. That is this
  restriction showing up.
- `native-lib.cpp` already contains the standard workaround: run the binary *through the system
  linker*, as in `execve("/system/bin/linker64", [linker64, binary, args…])`. The linker maps the
  file instead of `exec`-ing it, which SELinux allows. But it only does this for `node`
  (`native-lib.cpp:263`). The whole hook library is also only active "when LD_PRELOADed"
  (`native-lib.cpp:83`), and **nothing in the repo sets `LD_PRELOAD`**. The hooks are dormant.

**Consequence:** the current on-demand C/C++ toolchain (clangd LSP, clang) most likely does **not**
run on Android 10+ devices today. This needs confirming on a real device (I couldn't run one from
the cloud session). It's also the first thing the Language Pack system must solve, since every
Termux-based language depends on it.

### B. Termux binaries have Termux's install path baked in

Termux packages are built for the prefix `/data/data/com.termux/files/usr`. That path is compiled
into binaries (library search path `RUNPATH`, default config/include paths), shebang lines
(`#!/data/data/com.termux/files/usr/bin/python3`), and text files. Kodrix's own path
(`/data/data/com.kodrix.zohaib/files/…`) is *longer*, so it can't be patched in-place inside
binaries.

- `patch_elf.js` (repo root) already handles the library path by replacing it with a
  same-length `$ORIGIN/./././…`. **Caveat:** `$ORIGIN` is the directory of the file being loaded,
  so that replacement is correct for libraries in `usr/lib/` but points executables in `usr/bin/`
  at `usr/bin/`, which is wrong. Executables need `$ORIGIN/../lib`, padded to the same length.
- `native-lib.cpp:182` already has a runtime redirect from `…/com.termux/files` to the Kodrix
  files dir for `open`/`stat`/`access`, which is dormant for the same `LD_PRELOAD` reason.

### C. Pinned Termux URLs will break

`installCppToolchain()` downloads 7 hand-listed URLs with exact versions
(`clang_21.1.8-2_…deb`, `libllvm_21.1.8-2_…`, …). Termux's main repo generally serves only the
**current** build of each package. When Termux bumps clang, those URLs 404 and C/C++ install breaks
for every user. The fix is to resolve packages from Termux's `Packages` index at install time.

### D. Dependencies are resolved by hand

The same function lists clang's dependencies manually (libllvm, libffi, zstd, libxml2, liblzma,
ndk-sysroot). Termux's `Packages` index already declares `Depends:` for every package, so a
resolver makes this automatic and correct for any language.

### E. Python doesn't come from Termux today

Python 3.13.13 is a self-hosted zip on `KodrixMarketplace` releases (`TerminalViewModel.kt:3822`).
With the Termux-only decision, Python moves to Termux's `python` + `python-pip` packages (§8).

---

## 3. Architecture overview

```
KodrixMarketplace repo                         Kodrix app
──────────────────────                         ──────────
languages/index.json  ──fetch──►  LanguagePackManager ──► Languages side panel (browse/install/uninstall)
languages/<id>.json   ──fetch──►        │
                                        ▼
Termux mirror                   TermuxPackageManager
dists/stable/main/              (resolve deps from Packages index → download .deb →
  binary-<arch>/Packages ─────►  verify SHA256 → extract → fix paths → record files)
pool/…/*.deb            ─────►          │
                                        ▼
                                shared prefix:  filesDir/termux/usr/{bin,lib,include,share,etc}
                                        │
                                        ▼
                         Execution layer: libkodrix-exec.so (LD_PRELOAD, shipped in APK)
                         rewrites every exec of a filesDir binary → /system/bin/linker64 <binary>
                                        │
                  ┌─────────────────────┼──────────────────────┐
                  ▼                     ▼                      ▼
         Generic LSP launcher   Generic Run command    Generic syntax highlighter
         (manifest.lsp)         (manifest.run)         (manifest.syntax)
```

Four new pieces, each replacing hardcoded logic:

1. **Execution layer** (`libkodrix-exec.so`): makes downloaded binaries runnable. Solves constraint A.
2. **`TermuxPackageManager`**: a small in-app `apt`, covering constraints B, C and D.
3. **`LanguagePackManager` + manifests**: data-driven language definitions.
4. **Languages panel**: the user-facing install UI.

---

## 4. Execution layer (prerequisite for everything)

Generalise the dormant hooks in `native-lib.cpp` into a dedicated preload library, following
Termux's own `termux-exec` "system linker exec" approach (check its license before reusing code):

- **Ship it in the APK** (`jniLibs/<abi>/libkodrix-exec.so`) so it lives in `nativeLibraryDir`
  and is itself allowed to load.
- **Hook `execve`** (plus `execv*` variants and `posix_spawn`, which bionic implements internally
  and doesn't route through the hooked `execve`):
  - ELF file under `filesDir`: rewrite to `/system/bin/linker64 <file> <args…>`
    (`/system/bin/linker` on 32-bit).
  - Script with a shebang under `filesDir`: read the `#!` line, remap the interpreter path
    (Termux prefix → Kodrix prefix), and exec the interpreter through the linker the same way.
  - Anything else: pass through unchanged.
- **Hook path functions** (`open`, `openat`, `stat`, `lstat`, `access`, `readlink`, `opendir`, …):
  remap `/data/data/com.termux/files` → Kodrix prefix. This extends `native-lib.cpp`'s existing
  `do_redirect()`.
- **Set `LD_PRELOAD`** everywhere a pack binary can start: the terminal shell env (PtyBridge
  init), the LSP launcher, and the Run command. The library re-exports it to children, so
  `cargo → rustc → clang → ld.lld` chains keep working.

**Known side effect of the linker trick:** `/proc/self/exe` points at `linker64`, not the real
binary. Tools that find their own install location that way (Python's `sys.prefix`, clang's
resource dir, rustc's sysroot, Go's `GOROOT`) need an explicit env var or flag instead. That's
what the manifest `env` block is for (§6).

**Phase 0 is a device spike of exactly this layer.** It's a go/no-go gate: if Termux clangd and
python3 run from `filesDir` through it, the rest of the plan is straightforward engineering.

---

## 5. TermuxPackageManager (in-app mini-apt)

Replaces the hand-written download loop in `installCppToolchain()` with a general installer.

1. **Index:** download and cache
   `https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-<arch>/Packages`
   (`<arch>` = `aarch64` / `arm` / `x86_64` / `i686`, reusing the existing ABI mapping at
   `TerminalViewModel.kt:1048`). Parse the stanzas: `Package`, `Version`, `Depends`,
   `Pre-Depends`, `Filename`, `Size`, `Installed-Size`, `SHA256`.
2. **Resolve:** take the requested packages plus the transitive closure of `Depends`/`Pre-Depends`.
   Handle alternatives (`a | b`: first one available) and version constraints. Skip packages
   already installed at that version, plus an ignore-list of Termux-app plumbing that makes no
   sense in Kodrix (`termux-exec`, `termux-tools`, `termux-am`, …).
3. **Show size first:** sum `Size` (download) and `Installed-Size` so the panel can say
   "Rust — 310 MB download, 1.1 GB installed" before the user commits.
4. **Download + verify:** fetch each `.deb` from `Filename` and **check its SHA256 against the index**.
   Today's installer has no integrity check at all. (Later hardening: verify the index's `InRelease`
   GPG signature.)
5. **Extract:** reuse `extractDataTarFromDeb()` and the pure-Java XZ path already in
   `TerminalViewModel.kt:1129–1164`, stripping `./data/data/com.termux/files/` as today.
   Also support `data.tar.gz`/`.zst` if encountered.
6. **Install into one shared prefix**, `filesDir/termux/usr/`, the same layout as real Termux.
   One shared prefix (rather than per-language folders like today's `versions/clang/21.1.8/`)
   means shared libraries (`libc++`, `zlib`, `openssl`, …) are installed once, and cross-language
   tool chains work naturally (e.g. a compiler invoking `clang` as its linker).
7. **Fix paths after install:**
   - Rewrite shebangs and text files containing the Termux prefix (text can grow in length).
   - Library lookup: set `LD_LIBRARY_PATH=$PREFIX/lib` in every launch env (bionic searches it
     before `RUNPATH`). Optionally also patch `RUNPATH` in-app per constraint B, using
     `$ORIGIN/../lib` for executables.
8. **Record ownership:** a small install database (`filesDir/termux/var/lib/kodrix/installed.json`)
   mapping *package → version → files*, and *pack → packages*. That enables clean uninstall and
   reference counting (don't delete `libllvm` while another installed pack still needs it) and
   powers "update available" checks against the index.

---

## 6. Language Pack manifest

Hosted in `KodrixMarketplace` under a new `languages/` folder:

```
languages/
  index.json          # catalog shown in the Languages panel
  c-cpp.json
  python.json
  rust.json
  icons/c-cpp.png …
```

### `index.json`

```json
{
  "schema": 1,
  "packs": [
    { "id": "c-cpp",  "name": "C / C++", "summary": "Clang compiler + clangd", "icon": "icons/c-cpp.png",  "manifest": "c-cpp.json" },
    { "id": "python", "name": "Python",  "summary": "Python 3 + pip + pylsp",  "icon": "icons/python.png", "manifest": "python.json" },
    { "id": "rust",   "name": "Rust",    "summary": "rustc, Cargo, rust-analyzer", "icon": "icons/rust.png", "manifest": "rust.json" }
  ]
}
```

### Pack manifest fields

| Field | Required | Meaning |
|---|---|---|
| `schema` | ✓ | Manifest format version. The app hides packs with a newer schema than it understands and says "Update Kodrix to install this language". |
| `id`, `name`, `description`, `icon` | ✓ | Identity + display. |
| `languages[]` | ✓ | `{ languageId, extensions[], filenames[] }`: which files this pack handles (e.g. `filenames: ["Cargo.toml"]`). Replaces `getLanguageId()`. |
| `termux.packages[]` | ✓ | Top-level Termux package names; dependencies are resolved automatically. |
| `termux.ignoreDeps[]` | | Pack-specific extra ignore-list entries. |
| `requiresPacks[]` | | Other packs that must be installed first (e.g. a pack whose pip packages need `c-cpp` to build native extensions). |
| `env{}` | | Env vars for everything this pack launches, e.g. `PYTHONHOME`, `CPATH`. Supports the variables below. |
| `postInstall[][]` | | Commands run once after install, as **argv arrays, not shell strings** (e.g. `pip install python-lsp-server`). |
| `verify` | | `{ command[], expect }`: a smoke test run after install; install is marked failed if output doesn't contain `expect`. |
| `lsp` | | `{ command[], initializationOptions{}, prepare }`: how to start the language server over stdio. `prepare` names a built-in Kotlin hook (see below). |
| `run` | | `{ file, project: { detect, command } }`: templates for the Run button. `file` runs the current file; `project` applies when a marker file like `Cargo.toml` exists. |
| `syntax` | | `{ keywords[], types[], constants[], lineComment, blockComment[2], stringDelimiters[] }`: fed to a generic highlighter replacing `SyntaxHighlighter.kt`'s per-language lists. (TextMate grammars could replace this later.) |

**Variables** available in `env`, `lsp`, `run`, `postInstall`, `verify`:
`$PREFIX` (`filesDir/termux/usr`), `$HOME`, `$TMPDIR`, `{file}`, `{dir}`, `{stem}`, `{project}`.

**Built-in hooks (`lsp.prepare`).** Some language smarts don't fit in JSON. Today's
`ensureCompileCommands()` (`TerminalViewModel.kt:967`) generates `compile_commands.json` so clangd
resolves project includes. Rather than scripting that in JSON, it stays in Kotlin as a named hook
(`"prepare": "compile-commands"`) that any manifest can opt into. New hooks need an app update,
but new *languages* don't.

### Examples

These show intent. Exact Termux package names and flags get confirmed against the live index in Phase 1.

**C / C++**: replaces `installCppToolchain()` and the C++ branch of `startNativeLsp()`:

```json
{
  "schema": 1,
  "id": "c-cpp",
  "name": "C / C++",
  "description": "Clang compiler and clangd language server",
  "icon": "icons/c-cpp.png",
  "languages": [
    { "languageId": "c",   "extensions": ["c", "h"] },
    { "languageId": "cpp", "extensions": ["cpp", "cc", "cxx", "hpp", "hh", "hxx"] }
  ],
  "termux": { "packages": ["clang", "ndk-sysroot"] },
  "env": { "CPATH": "$PREFIX/include" },
  "verify": { "command": ["$PREFIX/bin/clang", "--version"], "expect": "clang version" },
  "lsp": { "command": ["$PREFIX/bin/clangd", "--stdio"], "prepare": "compile-commands" },
  "run": {
    "file": "clang++ {file} -o $TMPDIR/{stem} && $TMPDIR/{stem}",
    "project": { "detect": "Makefile", "command": "make" }
  },
  "syntax": {
    "keywords": ["if", "else", "for", "while", "return", "struct", "class", "namespace", "template", "…"],
    "types": ["int", "char", "void", "bool", "auto", "…"],
    "lineComment": "//", "blockComment": ["/*", "*/"], "stringDelimiters": ["\"", "'"]
  }
}
```

**Python**: moves Python from the self-hosted zip to Termux:

```json
{
  "schema": 1,
  "id": "python",
  "name": "Python",
  "languages": [ { "languageId": "python", "extensions": ["py", "pyw"] } ],
  "termux": { "packages": ["python", "python-pip"] },
  "env": { "PYTHONHOME": "$PREFIX" },
  "postInstall": [ ["$PREFIX/bin/python3", "-m", "pip", "install", "python-lsp-server"] ],
  "verify": { "command": ["$PREFIX/bin/python3", "--version"], "expect": "Python 3" },
  "lsp": { "command": ["$PREFIX/bin/python3", "-m", "pylsp"] },
  "run": { "file": "python3 {file}" },
  "syntax": { "keywords": ["def", "class", "import", "from", "return", "…"], "lineComment": "#", "stringDelimiters": ["\"", "'", "\"\"\"", "'''"] }
}
```

**Rust**: a brand-new language added purely as JSON. This is the proof of the whole system:

```json
{
  "schema": 1,
  "id": "rust",
  "name": "Rust",
  "languages": [ { "languageId": "rust", "extensions": ["rs"], "filenames": ["Cargo.toml"] } ],
  "termux": { "packages": ["rust", "rust-analyzer"] },
  "verify": { "command": ["$PREFIX/bin/rustc", "--version"], "expect": "rustc" },
  "lsp": { "command": ["$PREFIX/bin/rust-analyzer"] },
  "run": {
    "file": "rustc {file} -o $TMPDIR/{stem} && $TMPDIR/{stem}",
    "project": { "detect": "Cargo.toml", "command": "cargo run" }
  },
  "syntax": { "keywords": ["fn", "let", "mut", "impl", "match", "struct", "enum", "trait", "pub", "use", "…"], "lineComment": "//", "blockComment": ["/*", "*/"], "stringDelimiters": ["\""] }
}
```

---

## 7. App-side changes

| Today | Becomes |
|---|---|
| `getLanguageId()` `when` block | lookup in installed packs' `languages[]` |
| `SyntaxHighlighter.kt` per-language keyword lists | one generic highlighter driven by `manifest.syntax` (built-in lists kept as fallback for languages without a pack, e.g. Kotlin/JS) |
| `startNativeLsp()` `when (langId)` branches | one path: resolve pack → run `lsp.prepare` hook → substitute variables → `launchLspClient()` (existing, unchanged) with `LD_PRELOAD` + pack `env` |
| `installCppToolchain()`, `installPythonInBackground()`, `installPylspIfNeeded()` | `LanguagePackManager.install(packId)` → `TermuxPackageManager` |
| "File opened → auto-install toolchain" | kept, but generic: opening an `.rs` file with Rust not installed shows "Install Rust support?" pointing at the panel |
| n/a | new **Languages** sidebar mode: catalog from `index.json`, installed/update badges, size before install, progress (reusing `BinaryManager`'s notification helpers), uninstall |

Out of scope, unchanged: Node and Git stay bundled in the APK (`libnode_bin.so`, `libgit_bin.so`)
and managed by `BinaryManager`/`WrapperManager`; the Open VSX extension marketplace
(`ExtensionManager`) is a separate concern.

---

## 8. Migration of existing languages

- **C / C++:** existing installs live in `filesDir/versions/clang/<ver>/`. On first launch after
  the update, treat them as "not installed" and offer a one-tap reinstall into the shared prefix,
  then delete the old folder. No in-place conversion: the old layout moved headers into a custom
  `sysroot/`, which the shared prefix no longer needs.
- **Python:** existing installs from the self-hosted zip (`versions/python/3.13.13`) work the same
  way. Offer reinstall from Termux, then remove the old folder. The Python version will then follow
  Termux's cadence rather than your own builds. See open questions.
- **`marketplace/python-support/manifest.json`** and similar metadata-only entries are superseded
  by `languages/` and can be retired.

---

## 9. Security

- Every `.deb` is SHA256-verified against the Termux index, a new protection vs. today.
- Manifests define commands that run on users' devices (`postInstall`, `lsp`, `run`), so **anyone who
  can push to `KodrixMarketplace` can run code on every Kodrix install**. Protect that repo: branch
  protection on `main`, required 2FA, limited collaborators.
- `postInstall`/`lsp`/`verify` are argv arrays, not shell strings, so a manifest can't smuggle in
  shell metacharacters. `run` templates are shell (they run visibly in the terminal, like a user
  typing them).
- Later hardening: verify Termux's `InRelease` GPG signature on the index.

---

## 10. Phased plan

| Phase | Deliverable | Exit criterion |
|---|---|---|
| **0 — Execution spike** | `libkodrix-exec.so` (execve→linker64, shebang handling, Termux path remap) + `LD_PRELOAD` in terminal/LSP launch | On a real Android 10+ device: Termux `clangd --version` and `python3 -c 'print(1)'` run from `filesDir`. **Go/no-go gate.** Also confirms (and likely fixes) today's C/C++ breakage. |
| **1 — TermuxPackageManager** | index fetch/parse, dependency resolver, SHA256-verified download, extract, path fixups, install DB, uninstall with refcounts | Installs `clang` with deps resolved automatically (no hand list); uninstall removes exactly what it added. |
| **2 — Packs + generic engine** | manifest schema + `LanguagePackManager`, generic LSP launcher / run / highlighter; migrate **C/C++** | C/C++ works end-to-end from `c-cpp.json` with the old `when` branches deleted. |
| **3 — Languages panel** | sidebar UI: browse, sizes, install progress, uninstall, updates | User installs C/C++ from the panel with no file-open trigger. |
| **4 — Prove extensibility** | migrate **Python** to Termux; add **Rust** purely by pushing `rust.json` | Rust works on a device **without an app update**. |

---

## 11. Open questions for you

1. **Test devices:** what Android versions/phones can you test Phase 0 on? That gate needs a real device.
2. **Python version cadence:** moving to Termux means Python follows Termux's version (currently
   3.12/3.13-era). OK to drop your self-hosted 3.13.13 zip?
3. **First new languages after Rust:** Go, Java/Kotlin (OpenJDK is large), PHP, Ruby, Lua? This
   sets which built-in hooks (§6) might be needed.
4. **Storage policy:** packs can be large (Clang ≈ 150 MB download). Should the panel warn above
   some size, or offer "install to external storage" later?
5. **Shared-dependency uninstall:** when the last pack using `libllvm` is removed, delete it
   immediately or keep it cached?
