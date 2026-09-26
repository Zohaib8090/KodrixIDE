# VS Code Extensions — Plan (draft for owner review)

**Status:** Draft, 2026-09-26. Nothing here is built yet. The owner reviews this before any
work starts; open questions are collected in §11.

## 0. Goal

Let people install extensions from **Open VSX** (the open VS Code extension registry) with one
tap, and control exactly which ones run.

What "support" means, in order of priority:

| Level | What it covers | Examples | Runs code? |
|---|---|---|---|
| **1: Declarative** | Color themes, syntax highlighting (TextMate grammars), snippets, language settings (brackets, comments, auto-close), file icon themes | One Dark Pro, Dracula, Material Icon Theme, "Language support for X" grammar-only packs | **No.** Only data files are read |
| **2: Language servers** | Extensions that are mostly a language server | YAML, Tailwind CSS, Vue, Svelte, Prisma, TOML, Astro | **Yes.** A server process runs on the built-in Node |
| **3: Full extension host** | Extensions that call the VS Code API from JavaScript | GitLens, Prettier, ESLint UI, Docker | Yes, needs a VS Code API re-implementation |

**This plan covers Levels 1 and 2 and the "which extensions run" control system.** Level 3 is
out of scope (see §10).

### Mission fit (from HANDOFF.md §0)
- **One tap, no app update:** extensions come straight from Open VSX; launch rules for Level 2
  live in the online registry, like runtimes.
- **Only what you use is loaded:** an extension does nothing until a matching file is opened,
  and anything can be disabled or stopped (§5).
- **Clear to a non-expert:** plain labels, full error text on failure.
- **Respectful of the phone:** Level 1 costs no background CPU; Level 2 servers start on
  demand and stop when idle.

## 1. Current state

| Piece | Where | State |
|---|---|---|
| Open VSX search + details | `TerminalViewModel.searchExtensions` / `setActiveExtensionDetail` (~line 2365) | Works: lists results and versions |
| Installing a `.vsix` | — | **Missing.** Search results can't actually be installed and used |
| Enable/disable | `TerminalViewModel.toggleExtensionEnabled` (~line 1791): a set of disabled IDs in SharedPreferences | Exists, but nothing reads it to load or unload anything |
| Syntax highlighting | `ui/SyntaxHighlighter.kt`: hard-coded keyword lists for md, kt/java, js/ts/json, c/cpp, py | Limited; no themes |
| Editor | `ui/CodeEditor.kt`: one Compose `BasicTextField` + a `VisualTransformation` that re-colors the whole file on every change | Slow on large files; can't host TextMate |
| LSP client | `lsp/LspClient.kt` + `TerminalViewModel.startRuntimeLsp` | Works; reusable for Level 2 |
| Built-in Node | `libnode_bin.so` | Works; runs Level 2 servers |

## 2. Architecture

```
Open VSX ──download .vsix──▶ ExtensionInstaller ──unzip──▶ files/extensions/<id>-<ver>/
                                   │
                                   ▼
                          ExtensionRegistry (what's installed, what it contributes)
                                   │
                    ┌──────────────┼──────────────────┬───────────────────┐
                    ▼              ▼                  ▼                   ▼
             ThemeService   GrammarService      SnippetService     ServerExtensionHost
            (color theme,  (TextMate grammar   (snippets into      (Level 2: start/stop
             icon theme)    per language)        completion)        language servers)
                    │              │                  │                   │
                    └──────────────┴────────┬─────────┴───────────────────┘
                                            ▼
                         ExtensionControl (§5): decides what's ON for this project
                                            ▼
                                   Editor (Sora Editor, §3)
```

New code lives in `shared/src/androidMain/kotlin/com/kodrix/zohaib/extensions/`.

## 3. Editor: switch to Sora Editor (prerequisite for Level 1)

The current `BasicTextField` can't render TextMate grammars efficiently and will not scale to
big files. **Sora Editor** (`io.github.rosemoe:editor` + `language-textmate`, LGPL-2.1,
Android View) is a mature open-source Android code editor with built-in:
- TextMate grammar highlighting and VS Code JSON themes,
- incremental rendering (large files stay smooth),
- auto-completion popup, line numbers, bracket matching, diagnostics underlines, pinch zoom.

**Plan:**
- Embed it in Compose with `AndroidView` inside `CodeEditor.kt`, keeping the existing
  viewport/tab/split logic in `TerminalViewModel` unchanged.
- Bridge the existing LSP features: completion → Sora's completion provider; diagnostics →
  Sora's diagnostics container; `applyCompletion` logic reused.
- Keep `SyntaxHighlighter.kt` only as a fallback until the built-in grammars (§4.2) cover the
  same languages, then delete it.
- **Licensing:** LGPL-2.1 is fine for an MIT app when used as an unmodified library
  dependency. Add it to README acknowledgements.
- **Desktop app:** Sora is Android-only. The desktop editor keeps its current highlighting for
  now (see §11, Q6).

**Acceptance:** opening, editing, saving, tabs, split view, LSP completion and diagnostics all
work as today; a 10,000-line file scrolls and types without visible lag on the owner's Samsung.

## 4. Level 1 — declarative extensions

### 4.1 Install flow
1. User taps **Install** on an Open VSX result (or picks a local `.vsix`, reusing the existing
   sideload button).
2. Download the `.vsix` from the URL in Open VSX's API (`files.download`) over HTTPS. Verify its
   SHA-256 against the value Open VSX publishes for that file (the exact API field is checked
   during implementation); refuse to install on mismatch.
3. Unzip to `files/extensions/<publisher>.<name>-<version>/` (the `extension/` folder inside
   the `.vsix`). Reject zip entries with `..` or absolute paths.
4. Parse `package.json` → `contributes` and build an `ExtensionManifest` (§6).
5. If it contains **only declarative contributions** → installed and enabled straight away.
   If it also has code (`main`/`browser` entry), see §4.4.

### 4.2 Contributions supported
| `contributes` key | What Kodrix does |
|---|---|
| `themes` | Adds to Settings → Color Theme. Loads the VS Code theme JSON into Sora. One active at a time |
| `iconThemes` | Adds to Settings → File Icon Theme. File explorer uses its icons. One active at a time |
| `grammars` + `languages` | Registers the TextMate grammar and file extensions/filenames for a language |
| `languages[].configuration` | Brackets, comment toggling, auto-closing pairs, indentation rules |
| `snippets` | Snippets appear in the completion list, merged with LSP suggestions |

Unknown keys are ignored and listed on the extension's detail page as "Not supported in Kodrix"
so people know what they're missing.

**Built-in grammars:** ship a small set of grammars/themes in the APK (JS/TS, HTML, CSS, JSON,
Markdown, Python, C/C++, Kotlin, Rust, Shell, YAML) so highlighting works offline with no
installs, replacing today's keyword lists.

### 4.3 Updates
- Check Open VSX for newer versions when Marketplace opens (and at most once a day in the
  background). Show an **Update** button; a global "Auto-update extensions" switch (default off).
- Keep the previous version on disk until the new one installs and loads successfully, then
  delete it (so a bad update can be rolled back from the detail page).

### 4.4 Extensions that contain code
Most theme/grammar packs have no code. For those that do (`main` field):
- If Kodrix can use their declarative parts, install those and show a clear note: *"This
  extension also contains code that Kodrix can't run yet. Themes/highlighting from it work;
  its commands and features don't."*
- If they're in the Level 2 registry (§7), offer the language-server part.
- Otherwise the Install button says **"Not supported"** with the reason.

## 5. Extension control: choose what runs (owner's requirement)

The owner wants to decide exactly which extensions run and which don't. This is the central
UI of the feature.

### 5.1 States
Every installed extension has one of these states:

| State | Meaning | Uses resources? |
|---|---|---|
| **Enabled** | Active everywhere | Level 1: none. Level 2: server starts when a matching file opens |
| **Enabled for this project only** | Active only in the current project | Same, only in that project |
| **Disabled for this project** | Off in this project, on elsewhere | No, in this project |
| **Disabled** | Installed but completely off: nothing is loaded, no server, no themes/grammars | No |

Disabling never deletes the files. Re-enabling is instant, no re-download. **Uninstall** is a
separate action that deletes them.

### 5.2 Per-feature switches
An extension can contribute several things. The detail page lists each one with its own
switch, so the owner can keep a pack's grammar but turn off its snippets, or keep a language
server's highlighting but not run its server:

```
YAML (redhat.vscode-yaml)                     [ Enabled ▾ ]
 ├ Syntax highlighting for .yaml, .yml        [■ on ]
 ├ Snippets (12)                              [□ off]
 └ Language server (autocomplete, errors)     [■ on ]   Starts: When I open a YAML file ▾
```

### 5.3 Running control for language servers (Level 2)
For every server-type extension, a **"Starts"** setting:
- **When I open a matching file** (default): lazy start
- **Always (when the project opens)**
- **Never** (keeps highlighting/snippets, no server)

Plus a **Running** panel (Extensions → Running tab), like Android's running services:

```
Running now                                    RAM      Since
 YAML language server       yaml               48 MB    3 min    [Stop] [Restart]
 Tailwind CSS server        tailwind           95 MB    12 min   [Stop] [Restart]
Stopped automatically after 10 min idle: Vue language server
```

- **Idle stop:** servers with no matching file open for N minutes (default 10, adjustable) are
  stopped and restart automatically when needed.
- **Limit:** a setting for the maximum number of servers running at once (default 3); when
  exceeded, the least recently used one is stopped, with a small notice.

### 5.4 Conflicts
When two enabled extensions contribute the same thing (two grammars for `.vue`, two servers for
YAML, two icon themes), Kodrix doesn't guess: a **"Choose which one to use"** prompt appears
the first time, with a "Change later" link on each extension's page. Built-in support counts as
one of the options.

### 5.5 Safe mode
Settings → **Start without extensions**: one switch that disables everything (for when an
extension breaks the editor). The existing runtime Safe Mode can turn this on too.

### 5.6 Where the choices are stored
- **Global** choices: `files/extensions/state.json` (not SharedPreferences: easier to back up and
  inspect; written atomically).
- **Per-project** choices: `<project>/.kodrix/extensions.json`, so they travel with the project
  (git clone on another device keeps the same setup). Contains only IDs and switches, no files.
- The existing `disabled_extensions` pref is migrated into `state.json` once, then removed.

## 6. Data model

```kotlin
data class ExtensionManifest(
    val id: String,              // "publisher.name"
    val version: String,
    val displayName: String,
    val description: String,
    val installDir: File,
    val themes: List<ThemeContribution>,
    val iconThemes: List<IconThemeContribution>,
    val languages: List<LanguageContribution>,   // ids, extensions, filenames, configuration file
    val grammars: List<GrammarContribution>,     // scopeName, path, language
    val snippets: List<SnippetContribution>,     // language, path
    val server: ServerContribution?,             // from the Level 2 registry, not package.json
    val hasUnsupportedCode: Boolean,
    val unsupported: List<String>,               // contributes keys Kodrix ignores
)

enum class ExtensionState { ENABLED, ENABLED_PROJECT_ONLY, DISABLED_IN_PROJECT, DISABLED }
enum class ServerStart { ON_FILE_OPEN, ON_PROJECT_OPEN, NEVER }

data class ExtensionSettings(
    val state: ExtensionState,
    val disabledFeatures: Set<String>,   // e.g. "snippets", "server", "grammar:yaml"
    val serverStart: ServerStart,
)
```

## 7. Level 2 — language-server extensions

VS Code starts an extension's server from the extension's own JavaScript (`activate()`), which
Kodrix can't run (Level 3). But the server itself is usually a normal Node LSP server bundled
in the `.vsix`. So Kodrix needs a tiny **launch rule** per extension, stored in the online
registry (`versions.json` in KodrixMarketplace, new `"vscodeServers"` section) plus a built-in
copy in the app:

```json
"vscodeServers": {
  "redhat.vscode-yaml": {
    "languages": ["yaml"],
    "command": ["${node}", "${ext}/dist/languageserver.js", "--stdio"],
    "initializationOptions": {},
    "settings": { "yaml": { "validate": true } }
  }
}
```

- `${node}` = built-in Node, `${ext}` = the extension's install dir.
- Adding support for another server extension = one registry entry, no app update.
- The Marketplace marks such extensions **"Autocomplete supported"**; others show which parts
  work.
- Servers run exactly like today's web language servers (`startRuntimeLsp` path), under the
  control rules of §5.3.
- **First run prompt:** *"This extension runs a program on your phone to provide autocomplete.
  Allow?"* [Allow] [Not now]. Remembered per extension.

**Initial registry entries (to verify during implementation):** YAML, TOML (Even Better TOML),
Tailwind CSS, Vue (Vue - Official), Svelte, Astro, Prisma, Dockerfile, XML.

## 8. Security

- Only Open VSX (HTTPS) or a local file the user picked. SHA-256 checked for downloads.
- Zip extraction rejects path traversal; files never land outside the extension's folder.
- Level 1 never executes anything. Grammar regexes run through the TextMate engine with a time
  limit so a malicious grammar can't freeze the editor.
- Level 2 runs code only after the user allows it, only for registry-listed extensions, with the
  server's environment limited like the existing language servers (no GitHub token or API keys
  passed in).
- Follows the security rules in `SECURITY.md`.

## 9. Phases

Each phase ships on its own and is tested on the owner's phone before the next.

| Phase | Work | Done when |
|---|---|---|
| **A. Editor swap** | Sora Editor in `CodeEditor.kt`; LSP completion + diagnostics bridged; built-in grammars + default dark/light theme | Everything the editor does today still works; big files are smooth; highlighting for the built-in languages |
| **B. Install + themes** | `.vsix` download/verify/extract; `ExtensionManifest` parser; color + icon themes; Extensions list with Installed tab | Installing "One Dark Pro" from Open VSX changes the editor colors; an icon theme changes explorer icons |
| **C. Grammars, languages, snippets** | Register grammars and language configs; snippets in completion | Installing a grammar-only language extension adds highlighting for a new file type; snippets show up |
| **D. Control system** | States, per-project storage, per-feature switches, conflicts, "Start without extensions" | Owner can enable/disable globally and per project; choices survive restart and travel with a cloned project |
| **E. Language servers** | `vscodeServers` registry + launcher; Running panel; idle stop; server limit; first-run prompt | Installing YAML gives YAML autocomplete; the server appears in Running, stops when idle, and can be stopped/disabled |
| **F. Updates** | Update check, Update button, auto-update switch, rollback | A newer Open VSX version shows Update; installing it keeps the old one until the new one loads |

## 10. Out of scope (for now)

- **Running arbitrary extension JavaScript (Level 3):** needs a large re-implementation of the
  `vscode` API (commands, views, webviews, workspace…). Revisit only after Levels 1–2 are solid.
- **Microsoft's marketplace:** its terms forbid use outside Microsoft's products. Open VSX only.
- **Microsoft-only extensions** (Pylance, C/C++, Remote-SSH, Live Share, Copilot): not published
  to Open VSX.
- **Debug adapters (DAP), notebooks, webview panels.**

## 11. Open questions for the owner

1. **Editor swap (Phase A):** OK to replace the current editor with Sora Editor? It's the
   biggest change, but Level 1 isn't practical without it.
2. **Default for new language-server extensions:** start "When I open a matching file" (lazy,
   recommended) or "Never" until you turn it on?
3. **Idle stop time and server limit:** 10 minutes and 3 servers OK?
4. **Per-project settings file** (`.kodrix/extensions.json`) committed with projects: OK, or
   keep all choices on the device only?
5. **Auto-update extensions:** off by default OK?
6. **Desktop app:** Android only for now, or should the desktop app get extensions too (needs a
   different editor component)?
7. **Order:** is A→F right, or do you want the control system (D) earlier?

## 12. Risks

| Risk | Mitigation |
|---|---|
| Editor swap breaks existing editing features | Phase A alone, feature-parity checklist, keep old editor behind a setting until verified |
| TextMate highlighting too slow on low-end phones | Sora highlights incrementally in the background; built-in grammars tested on an old armeabi-v7a device/emulator |
| Server extensions change their bundle layout between versions | Launch rules are in the registry, so they can be fixed without an app update; weekly registry check (like runtimes) resolves each `command` path against the latest `.vsix` |
| Several servers use too much RAM | Lazy start, idle stop, server limit, Running panel |
| Open VSX outage | Installed extensions keep working offline; only install/update needs the network |
