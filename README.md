<div align="center">

<img src="https://img.shields.io/badge/Platform-Android%2010%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white"/>
<img src="https://img.shields.io/badge/Architecture-Multi--Arch-FF6B35?style=for-the-badge"/>
<img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge"/>
<img src="https://img.shields.io/badge/Status-Alpha-orange?style=for-the-badge"/>
<img src="https://img.shields.io/badge/Version-1.1.2-blue?style=for-the-badge"/>
<img src="https://img.shields.io/github/stars/Zohaib8090/KodrixIDE?style=for-the-badge&color=blue"/>

<br/>
<br/>

# Kodrix

### A fully standalone, native IDE for Android. No PC. No Termux. No compromises.

**Code from anywhere. Build for everywhere.**

*Built by [@Zohaib8090](https://github.com/Zohaib8090) — 17 years old, Karachi, Pakistan*

[Website](https://kodrixide.duckdns.org) · [Download APK](https://github.com/Zohaib8090/KodrixIDE/releases) · [Report Bug](https://github.com/Zohaib8090/KodrixIDE/issues) · [Request Feature](https://github.com/Zohaib8090/KodrixIDE/discussions)

</div>

---

## What is Kodrix?

Kodrix is a **complete, professional development environment** that runs entirely on your Android phone. Unlike other mobile code editors, it doesn't require Termux, a remote server, or a PC. Everything runs natively on-device.

Clone a repo, install npm packages, interpret Python scripts, compile low-level C files, invoke a native AI agent, run a dev server, debug your app — all from your phone.

> This is not a toy editor. This is a full IDE.

---

## Installation

1. Download the latest APK from [Releases](https://github.com/Zohaib8090/KodrixIDE/releases)
2. On your Android phone, go to **Settings → Security → Install unknown apps** and allow your file manager
3. Open the downloaded APK and tap **Install**
4. Open Kodrix and start coding

> **Minimum requirements:** Android 10+ (API 29) · Universal Architecture Support · ~500MB free storage · 4GB RAM recommended

---

## Features

### Editor & Intelligence
- **Multi-file Tabs** — Open multiple files simultaneously with unsaved change indicators
- **Split Screen** — View and edit files side by side horizontally or vertically
- **LSP Autocomplete** — Full Language Server Protocol integration providing intelligent code suggestions, diagnostics, and hover information natively
- **Python LSP (`pylsp`)** — Zero-config background installer; provides auto-completion, hover docs, and diagnostics for Python files
- **C/C++ LSP (`clangd`)** — On-demand Clang 21 toolchain download with `clangd` providing C/C++ completions and diagnostics
- **Syntax Highlighting** — Native color token parsing for C, C++, Python, Kotlin, JavaScript, TypeScript, HTML, CSS, Markdown and more
- **Smart Keyboard** — Extra keys row (ESC, TAB, arrows, CTRL) that sits above the soft keyboard
- **Font & UI Scale** — Adjustable font size and global UI scale for accessibility

### AI Agent Loop
- **Native Gemini Integration** — Fully operational local AI agent loop powered by Gemini. Ask questions, refactor code, and generate components directly within your editor workspace.

### Terminal
- **Real Terminal** — Full PTY-based terminal powered by the termux-terminal-emulator library
- **Multiple Sessions** — Run multiple terminal sessions simultaneously with tab switching
- **ANSI Support** — Full color and cursor control support
- **TUI Support** — Interactive CLI tools render correctly
- **CTRL Modifier** — Hardware-accurate CTRL key support (CTRL+C, CTRL+Z and more)

### Git & Source Control
- **Git over HTTPS** — Clone, commit, push, pull via libgit2 JNI bridge
- **GitHub OAuth** — Sign in with GitHub via deep link — no manual config needed
- **Source Control UI** — Visual git panel with commit, push, pull, branch switching, changes list and timeline
- **Auto-Stash Switching** — Seamlessly switch branches with automatic stash/pop
- **One-click Clone** — Browse your GitHub repos and clone with a single tap

### Runtimes & Toolchains
- **Node.js v25** — Full Node.js running on-device as native binary with full **npm** support
- **Python 3.13** — Fully standalone, isolated local Python execution layer built right into the app
- **Clang 21.1.8** — Full C/C++ toolchain auto-downloaded on first C/C++ file open; packages sourced from Termux repositories and extracted natively (pure-Java XZ decompression — no `xz` system binary required)
- **Auto Binary Updates** — Runtimes and Git binaries update automatically via GitHub releases

### Browser & DevTools
- **Built-in Browser** — Open your dev server directly inside the IDE
- **Auto Detection** — Automatically detects running dev servers and offers to open them
- **DevTools Console** — Browser console showing logs from your web app
- **Desktop Mode** — Switch between mobile and desktop user agent
- **Camera/Mic/File** — Full permission support for testing web apps
- **Zoom Support** — Pinch to zoom and text size controls

### Port Forwarding
- **One-tap Tunnels** — Expose localhost to the internet instantly via bore.pub
- **Auto Detection** — Automatically detects active dev server ports
- **Open Anywhere** — Open tunneled URLs in internal or external browser

### Debugging
- **Debug Console** — Variables, Watch, Call Stack, Breakpoints panel
- **Live Logcat** — Real-time system log viewer with filtering and color coding
- **Problems Panel** — Automatically parses interpreter/build errors with file and line info
- **Output Panel** — Dedicated output view for running tasks
- **Run & Debug** — Execute execution tasks directly from the debug panel

### Marketplace & Extensions
- **Extension Marketplace** — Browse and search VS Code extensions via Open VSX
- **Extension Install** — Install NPM and ZIP-based extensions with progress tracking
- **Sideloading** — Manually install local ZIP extensions for offline use

### Project Management
- **File Manager** — Full file explorer with create, rename, delete, copy, paste, cut
- **Project Import/Export** — Import and export projects as ZIP files
- **File Upload/Download** — Upload files from device storage into projects

### Updates & Settings
- **Auto Update Notifications** — Get notified when a new app version is available
- **Configurable Font Size** — Adjust editor and terminal font size
- **Configurable UI Scale** — Adjust icon and UI element sizes
- **Zero Termux Dependency** — Completely standalone APK
- **Android 15 Compatible** — Works with Android's W^X security policy

---

## Supported Frameworks & Languages

| Target | Status | Notes |
|--------|--------|-------|
| Python 3 | ✅ Working | Full runtime + `pylsp` autocomplete |
| React + Vite | ✅ Working | Use `@vitejs/plugin-react` (not swc variant) |
| Express / Node.js | ✅ Working | Full support |
| Vue + Vite | ✅ Working | Pure JS build |
| Svelte + Vite | ✅ Working | Pure JS build |
| Next.js | ✅ Working | Webpack mode, no Turbopack |
| C / C++ | ✅ Working | On-demand Clang 21 toolchain + `clangd` LSP |

---

## Architecture

```
Kodrix
├── Kotlin / Jetpack Compose (UI layer)
├── JNI Bridge (native-lib.cpp)
│   ├── PTY Bridge (forkpty → real terminal)
│   ├── Git Bridge (libgit2 → HTTPS clone/push/pull)
│   └── DNS Override (Google DNS for Node.js)
├── Terminal Engine (termux-terminal-emulator)
│   └── Full VT100/xterm emulation
├── Intelligence & AI Layer
│   ├── Native LSP Engine
│   │   ├── Python → pylsp (auto-installed via pip)
│   │   ├── C/C++ → clangd 21.1.8 (Termux .deb, pure-Java XZ extract)
│   │   └── JS/TS/HTML/CSS/JSON/Bash → Node.js language servers
│   └── Multi-Agent Loop Core (Gemini SDK runtime integration)
├── Bundled Binaries & Toolchains (jniLibs/)
│   ├── Multi-Architecture Native Support (arm64-v8a, armeabi-v7a, x86, x86_64)
│   ├── libnode.so (Node.js v25 runtime)
│   ├── python3 (Native Python 3.13 interpreter)
│   ├── libgit2.so (Git operations)
│   ├── libnext_swc.so (SWC compiler for Next.js)
│   ├── libcurl.so (HTTP/HTTPS)
│   ├── libssl.so / libcrypto.so (OpenSSL 3.x)
│   └── libicui18n/uc/data.so (Unicode support)
└── Zero-Termux Policy (no external dependencies)
```

---

## Building from Source

> For developers who want to modify or contribute to Kodrix.

**Prerequisites:**
- Android Studio Ladybug or newer
- Android NDK r27+
- CMake 3.22+
- JDK 17+
- Rust (for SWC binary compilation)

```bash
git clone https://github.com/Zohaib8090/KodrixIDE.git
cd KodrixIDE
./gradlew assembleRelease
# APK output: app/build/outputs/apk/release/
```

---

## Roadmap

- [x] Terminal with PTY
- [x] Git clone over HTTPS
- [x] GitHub OAuth login
- [x] npm install
- [x] React + Vite support
- [x] Next.js support
- [x] Built-in browser with DevTools
- [x] Port forwarding (bore.pub)
- [x] Multi-file tabs + split screen
- [x] Source control UI
- [x] Debug console + Logcat
- [x] Extension marketplace UI
- [x] Auto update notifications
- [x] Node/Git binary auto-update system
- [x] Termux terminal emulator integration
- [x] Syntax highlighting
- [x] Smart keyboard row
- [x] Run & Debug panel
- [x] Native Python 3 execution runtime
- [x] Multi-Architecture binary build profiles
- [x] LSP autocomplete (Python `pylsp`, JS/TS, HTML, CSS, JSON, Bash)
- [x] Integrated AI Agent loop (Gemini interface layer)
- [x] Native C/C++ cross-compiler toolchain (Clang 21 + clangd LSP)
- [ ] React Native support
- [ ] Flutter support

---

## Known Limitations

| Limitation | Reason | Planned Fix |
|------------|--------|-------------|
| No iOS support | Platform limitation | Not planned |
| Turbopack not supported | Needs native SWC recompile | In progress |

---

## Found a Bug?

Open an issue on GitHub with steps to reproduce.

---

## Acknowledgements

- [libgit2](https://libgit2.org/) — Git operations
- [Termux](https://termux.dev/) — Terminal emulator library & Clang/clangd packages
- [Node.js](https://nodejs.org/) — JavaScript runtime
- [Python](https://www.python.org/) — Python interpreter backend
- [Google Gemini](https://deepmind.google/technologies/gemini/) — AI Agent infrastructure
- [bore](https://github.com/ekzhang/bore) — Port forwarding
- [Open VSX](https://open-vsx.org/) — Extension marketplace
- [XZ for Java](https://tukaani.org/xz/java.html) — Pure-Java XZ decompression for Clang toolchain install

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

<div align="center">

Made with ❤️ by a 17-year-old developer from Karachi, Pakistan

If Kodrix helps you, consider [sponsoring](https://github.com/sponsors/Zohaib8090)

⭐ Star this repo if you find it useful!

</div>
