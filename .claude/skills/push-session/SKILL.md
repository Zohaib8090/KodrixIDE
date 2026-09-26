---
name: push-session
description: Save everything from the current chat session into the repo and push it, so the next session (a new chat, another PC, or the owner's Termux/proot phone setup) continues with full context. Use whenever the owner says "push for the session", "push the session", "save the session", "handoff", "I'm switching devices", "I'll continue on my phone / in Termux / in proot", "new chat session", or otherwise signals they are about to leave this session and pick the work up elsewhere. Also offer it (one line) when a long session is clearly wrapping up.
---

# Push the session

The owner works on Kodrix from several places: a Windows PC, new chat sessions, and a
Termux + proot environment on their phone. Only what's **in the git repo** travels between
them. Claude's local memory (`~/.claude/...`), the chat transcript, and anything said but not
written down are lost when the session ends.

So when the owner says "push for the session", the job is: **turn this session's knowledge
into repo files, commit, and push**, so a fresh Claude that clones the repo knows everything
this one knew.

## 1. Gather what this session learned

Go back through the whole conversation (and any local memory files for this project) and
collect:

- **Owner's instructions and rules**: anything phrased like "always…", "never…", "work like
  this", "ask me before…", preferences about style, credit, branches, testing, communication.
  These are the most important thing to carry over. The owner has said the rules they give
  should apply in every future session.
- **Project info the owner told you** that isn't obvious from the code: goals, decisions and
  their reasons, device/test setup, accounts/repos involved, plans.
- **What was done**: changes made, commits, PRs, builds, and their outcomes (succeeded,
  failed, untested).
- **What's pending**: next steps, open questions, things started but not finished, things
  that need testing on a device.
- **Environment knowledge**: setup steps or workarounds discovered (e.g. how to build on a
  given machine, errors hit and how they were fixed). Note which environment it applies to
  (Windows PC, Termux/proot, CI), because a fix for one usually doesn't apply to another.

Leave out secrets: API keys, tokens, passwords, keystore passwords, `local.properties`
contents. If a secret matters, write *where* it lives ("GitHub token is in the phone's
Termux `~/.git-credentials`"), never the value.

## 2. Write it into the repo

Put each kind of information where the next session will look for it. Claude Code
auto-loads `.claude/CLAUDE.md`, so that file is the entry point, and it should stay short.

| What | Where | How |
|---|---|---|
| Durable rules and instructions from the owner | `.claude/CLAUDE.md` → "Working rules" | Add or update bullets. Merge with the existing ones and don't duplicate. Remove a rule only if the owner withdrew it. |
| Current state: done / unverified / next steps / branch status | `documents/HANDOFF.md` | Update the relevant sections in place and bump "Last updated". Fix anything that's now false (e.g. "not merged" once it is). |
| Environment setup and workarounds | `documents/HANDOFF.md` → "Build & test" (add a subsection per environment) | Keep the concrete commands. |
| Anything big or topic-specific (a design, an investigation) | its own file in `documents/` | Link it from HANDOFF.md. |

Write for a reader with **zero context**: a new Claude that has never seen this chat. Use
concrete file paths, commands, and dates (absolute dates like 2026-09-25, not "yesterday").
Keep what's still true, correct what changed, and don't rewrite sections that didn't change.

If the owner gave rules in this session that conflict with existing ones in CLAUDE.md, the
newer instruction wins. Update the old bullet rather than leaving both.

## 3. Commit and push

Follow the repo's rules in `.claude/CLAUDE.md`. In particular:

1. **Branch**: commit on the working branch named in CLAUDE.md (currently
   `claude/confident-rubin-3wc23a`). If you're on `main` and it points at the same commit,
   switch to that branch first. If the branch doesn't exist locally, create it from the
   current HEAD tracking `origin/<branch>`.
2. **Credit**: before committing, ask the owner who gets credit (Only me / Me + Claude /
   Only Claude), exactly as CLAUDE.md describes. Don't push until they answer. Ask this
   together with any other question you have, so it's one round trip.
3. Stage **only** the handoff files you changed (CLAUDE.md, documents/…, this skill if
   edited). Don't sweep in unrelated work-in-progress; if other uncommitted changes exist,
   mention them and ask whether they should go in too.
4. Commit message: `Session handoff: <one-line summary of the session>`.
5. `git push origin <branch>`. If the push is rejected because the remote moved, `git pull
   --rebase` and push again. Never force-push.

## 4. Tell the owner how to pick it up

Reply briefly with:
- what you saved (a few bullets: new rules, state changes, env notes),
- the branch and commit you pushed,
- the commands to continue on the other device, e.g.

```bash
git clone https://github.com/Zohaib8090/KodrixIDE.git   # first time only
cd KodrixIDE
git fetch origin
git checkout claude/confident-rubin-3wc23a
git pull
```

and that a new Claude session there will read `.claude/CLAUDE.md` automatically. They can
say "read the handoff and continue" to get going.

## Starting a session on the other side

If you are the *new* session and the owner says something like "continue from the handoff"
or "pull the session": `git pull` on the working branch, read `.claude/CLAUDE.md`, then
`documents/HANDOFF.md`, then summarize back in a few lines what you understand the current
state and next step to be before starting work.
