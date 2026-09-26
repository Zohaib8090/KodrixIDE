# Contributing to Kodrix

Thanks for wanting to help build Kodrix! This guide covers how to get set up and the
standards a change needs to meet before it's merged.

By taking part you agree to follow our [Code of Conduct](CODE_OF_CONDUCT.md). Security
problems are **not** reported through issues. See [SECURITY.md](SECURITY.md).

## What Kodrix is aiming for

Kodrix is one Android app where anyone can code in many languages with zero setup: install
the app, pick a language, code. When you choose what to work on, keep in mind:

- **A language counts as supported only when it runs *and* has working autocomplete (LSP).**
  Don't advertise something that only half works.
- **New languages and runtime versions must not need an app update.** They come from the
  registry (`versions.json` in
  [KodrixMarketplace](https://github.com/Zohaib8090/KodrixMarketplace)) and the Termux
  package index. See `documents/LANGUAGE_PACKS.md`.
- **Plain, honest UI.** Users should see clear labels and full error messages, not codes or
  cut-off text.
- **Respect the phone.** Fast startup, no heavy work on the main thread, and permissions only
  when a feature needs them.

## Ways to help

- **Report bugs** with [Issues](https://github.com/Zohaib8090/KodrixIDE/issues): Kodrix
  version, Android version, phone model and CPU type (arm64-v8a, armeabi-v7a, …), steps to
  reproduce, and what you expected. Logs help a lot (Settings → Native Exec Bridge → View
  log, or `adb logcat`). Remove tokens and personal data first.
- **Suggest features** in [Discussions](https://github.com/Zohaib8090/KodrixIDE/discussions).
  For anything big, please discuss it before writing code so work isn't wasted.
- **Add a language** by adding an entry to the registry. Usually no app code is needed. See
  `documents/LANGUAGE_PACKS.md` §18.
- **Fix bugs or build features** with a pull request.

## Project layout

| Folder | What's in it |
|---|---|
| `shared/` | Most of the app (Kotlin Multiplatform + Compose). Android code is in `src/androidMain`. |
| `androidApp/` | The Android entry point, native code (`src/main/cpp`) and bundled binaries (`src/main/jniLibs`) |
| `desktopApp/` | The Linux desktop app |
| `agentServer/` | The TypeScript server that connects the AI agent to model providers |
| `documents/` | Design docs. Start with `HANDOFF.md` and `LANGUAGE_PACKS.md`. |

## Building

You need JDK 17, the Android SDK with **NDK `30.0.14904198`** and CMake 3.22.1.

```bash
git clone https://github.com/Zohaib8090/KodrixIDE.git
cd KodrixIDE
./gradlew :androidApp:assembleDebug
```

APKs are written to `androidApp/build/outputs/apk/debug/`, one per CPU type. Every push also
builds APKs in GitHub Actions, which you can download from the run's **Artifacts**.
`documents/HANDOFF.md` §2 has platform-specific build tips (for example, Windows).

## Pull request standards

1. **One change per pull request.** Keep it focused. Refactors go separately from features
   and fixes.
2. **Branch from `main`** and give the branch a clear name (`fix/terminal-git-permission`,
   `feat/lua-lsp`).
3. **It must build.** The GitHub Actions APK build has to pass.
4. **Test it on a real device** (or an emulator for UI-only changes). In the description,
   say which Android version, device and CPU type you tested on and what you checked. If you
   couldn't test something, say so. Being honest about that is better than guessing.
5. **Follow the security rules** in [SECURITY.md](SECURITY.md#security-rules-for-contributors):
   no secrets, verified downloads, no shell strings built from untrusted text.
6. **Match the surrounding code.** Use the same naming, structure and comment style as the
   file you're editing. Kotlin follows the
   [official style](https://kotlinlang.org/docs/coding-conventions.html). Comments explain
   *why*, not what.
7. **Remember Android's rules.** Android 10+ won't run downloaded files directly. Anything
   that runs a binary from app storage must go through the linker path
   (`runtime/RuntimeExec.kt`). Read `documents/NATIVE_FIX_SUMMARY.md` before touching
   `jniLibs/` or native code.
8. **Don't commit build output or junk:** no `build/` folders, APKs, logs, `local.properties`,
   crash dumps or large downloaded binaries. Big binaries belong in KodrixMarketplace
   releases.
9. **Update the docs** when you change how something works (`README.md`, `documents/`).
10. **Write clear commit messages.** A short summary line in the imperative ("Fix git
    permission error in terminal"), then a blank line and the reason for the change.

11. **Show proof it works.** Fill in the pull request template: what you ran, the results,
    and **screenshots or a screen recording** for anything that changes what the user sees
    (UI, terminal output, error messages). Pull requests without test evidence won't be
    merged.

## AI-assisted contributions

Code written with AI tools (Claude, Copilot, ChatGPT, Gemini, Cursor, …) is welcome. Kodrix
itself is built with AI help. It has to meet the same bar as any other code, plus a few
extra rules, because AI tools can produce code that looks right but was never actually run.

1. **Say you used AI.** Tick the box in the pull request template and name the tool. There's
   no penalty for it. It just tells reviewers where to look harder.
2. **You're the author.** You are responsible for every line, whoever or whatever typed it.
   Read and understand all of it before you open the pull request, and be ready to explain
   any part of it in review. "The AI wrote it" is not an answer to a review question.
3. **Run it yourself.** AI-written code must be built and tested by you on a real device or
   emulator, not only "it compiles". Attach:
   - **test results**: the commands you ran and their output (or a summary for long output),
   - **screenshots or a screen recording** showing the change working, before and after for
     UI or behavior changes,
   - the device, Android version and CPU type you tested on.
4. **No invented facts.** Check every API, library, Gradle setting, package name and version
   the AI suggested actually exists. Double-check anything to do with Android permissions,
   SELinux, native code or security. These are exactly the areas where AI tools guess wrong
   (see `documents/NATIVE_FIX_SUMMARY.md`).
5. **Keep it small and focused.** Don't submit large AI-generated rewrites, mass reformatting
   or "cleanup" of files unrelated to your change. Big changes need a Discussion first.
6. **Security rules still apply**, especially no secrets, no shell strings from untrusted
   text, and no disabled TLS checks. Never paste API keys, tokens or private code into an AI
   tool.
7. **Commit credit.** You may add a `Co-Authored-By:` line for the AI tool in your commit
   message. It's optional, but it has to be honest.

Pull requests that clearly haven't been run or read (made-up APIs, code that can't build,
missing test evidence) will be closed with a note asking for these points to be covered.

The project owner, [@Zohaib8090](https://github.com/Zohaib8090), will review your pull request. Changes may be requested. That's normal and not
a judgement of you, it's how we keep the project stable. Please be patient: Kodrix is
built and run by one person.

## License

By contributing, you agree that your contributions are licensed under the project's
[MIT License](LICENSE).
