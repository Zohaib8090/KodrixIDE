# Security Policy

Kodrix runs code, downloads toolchains and handles GitHub and AI-provider credentials on
people's phones, so security problems matter here. Thank you for helping keep users safe.

## Supported versions

Only the latest release gets security fixes. Please check that you can reproduce the problem
on the newest APK from [Releases](https://github.com/Zohaib8090/KodrixIDE/releases) or on the
`main` branch before reporting.

| Version | Supported |
|---|---|
| Latest release (1.2.x) | ✅ |
| Older releases | ❌ Please update |

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a security problem.** Public
reports put users at risk before a fix is out.

Report it privately instead:

1. Go to the repository's **[Security tab → Report a vulnerability](https://github.com/Zohaib8090/KodrixIDE/security/advisories/new)**.
2. Include:
   - what the problem is and what an attacker could do with it,
   - the Kodrix version and Android version / device you tested on,
   - steps to reproduce, or a proof of concept,
   - any logs, with tokens and personal data removed.

### What happens next

Kodrix is maintained by one developer, so these are targets rather than guarantees:

| Step | Target |
|---|---|
| Acknowledge your report | within 7 days |
| Confirm whether it's a real issue and share a plan | within 14 days |
| Release a fix for a confirmed high-severity issue | as soon as possible, usually within 30 days |

You'll get updates in the private advisory thread. Once a fix is released, the advisory is
published and you're credited by name (or kept anonymous if you prefer).

Please give us a reasonable chance to fix the issue before sharing it anywhere else.

## Scope

**In scope:**
- The Android app (`androidApp/`, `shared/`) and the desktop app (`desktopApp/`)
- The native code (`androidApp/src/main/cpp/`), including the exec shim `kodrix_exec.c`
- The agent server (`agentServer/`) and how the app launches it
- How runtimes and extensions are downloaded, verified and installed (the Termux package
  installer, the marketplace, the `versions.json` registry)
- Handling of GitHub OAuth tokens, AI-provider API keys and other stored credentials
- The GitHub Actions workflows in this repository

**Examples of what we want to hear about:**
- A way for a web page, project file, extension or downloaded package to run code or read
  files it shouldn't
- Credentials (GitHub token, API keys) being leaked, logged, or stored unencrypted
- Downloads that can be tampered with (missing or bypassable checksum checks, HTTP instead
  of HTTPS)
- Shell or command injection through file names, project names, git URLs or agent tool calls
- The local agent server or tunnel exposing something to other devices or the internet
  without the user choosing to

**Out of scope:**
- Code a user deliberately runs in their own terminal. Kodrix is an IDE, and running what
  you type is the point.
- Problems that need a rooted device, a device already compromised, or physical access to
  an unlocked phone
- Vulnerabilities in third-party packages (Termux packages, npm modules, language servers).
  Report those upstream. Tell us too if Kodrix makes them worse.
- Anything exposed by a port-forwarding tunnel (bore.pub) that the user opened on purpose
- Missing security headers on the project website, or automated scanner output with no
  demonstrated impact

## Safe harbor

We won't pursue or support legal action against anyone who researches and reports in good
faith under this policy. That means:
- only testing on your own devices and accounts,
- not accessing, changing or deleting other people's data,
- not degrading the service for others,
- giving us time to fix the issue before disclosing it.

## Security rules for contributors

All code merged into Kodrix must follow these rules. Reviewers will ask for changes if a pull
request breaks them.

1. **No secrets in the repository.** Never commit API keys, tokens, passwords, signing
   keystores (`*.jks`, `*.keystore`), `local.properties`, `.env` files or private keys. Use
   environment variables, Android's encrypted storage, or the agent server's `apiKeyRef`
   references (`env:`, `file:`), never literal keys.
2. **Verify everything you download.** Runtimes, packages and extensions must be fetched over
   HTTPS and checked against a SHA-256 hash (or a signed index) before they're installed or
   run. Never turn TLS verification off in new code.
3. **Don't build shell commands from untrusted text.** File names, project names, git URLs,
   package names and anything an AI model produced must be passed as separate arguments
   (`ProcessBuilder` with a list) or properly quoted, never pasted into a shell string.
4. **Keep credentials out of logs and files.** Don't log tokens, Authorization headers or
   full request bodies. Don't write credentials to `.netrc`, `hosts.yml` or other plain-text
   files. Pass them in memory (see `git-credential-kodrix`).
5. **Local servers listen on `127.0.0.1` only.** The agent server, language servers and
   anything else Kodrix starts must not listen on all interfaces. Exposing something to the
   network must be a clear choice by the user (like starting a tunnel).
6. **Ask for permissions only when they're needed**, with an explanation, and never more than
   the feature requires.
7. **AI agent tools that change files, run commands or touch the network** must go through
   the user-approval flow unless the user has explicitly turned on auto mode.
8. **Native code** (`kodrix_exec.c`, `native-lib.cpp`) must check buffer sizes and return
   values. Changes to the exec shim need a clear explanation of what they allow to run.
9. **New dependencies** must come from the official source (Maven Central, Google, npm) and
   be pinned to a version. Mention every new dependency in the pull request description.

If you're not sure whether a change is safe, say so in the pull request. Asking is always
fine.
