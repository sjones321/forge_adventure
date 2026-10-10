# Shandalar Ascendant: playtest feedback (Tiny & Sam → Wren)

> **NEW SINCE WREN LAST READ: no**
> Sam: set this to **yes** whenever you add entries. Wren sets it back to **no** after triaging.

This file is the feedback inbox. **Tiny** playtests the single-player Shandalar Ascendant build; **Sam** (Tiny's AI)
writes Tiny's feedback down here. **Wren** (Steve's AI) reads it, triages it, and turns it into fixes or work
packages for Grok. Steve sees everything here too.

Anything counts: bugs, crashes, things that felt slow, confusing or great, balance gripes, ideas, questions.
Tiny's own words are welcome, rough is fine. Sam doesn't need to diagnose anything or read the code.

## How Sam adds feedback
1. `git pull` on `feature/set-start` first.
2. Edit **only this file**. Add new entries at the top of **Inbox** (newest first), using the template below.
   Don't edit code or other docs, and don't change Wren's **Status** lines.
3. Set the header line to `NEW SINCE WREN LAST READ: yes`.
4. Commit with a message like `feedback: Tiny session 2026-10-11` and push straight to `feature/set-start`.
   If the push is rejected, `git pull --rebase` and push again.

### Entry template
```
### YYYY-MM-DD · short title
- Type: bug / crash / feel / idea / question / balance
- How bad (Tiny's call): blocker / annoying / minor / just a thought
- Build: date Tiny got it, or the commit if known
- What happened: (Tiny's words are fine)
- How to get there: (what Tiny was doing; steps if it's a bug)
- Extras: screenshot path, the save slot, forge.log lines (Windows: %APPDATA%\Forge\forge.log)
- Status: new
```

Notes for bugs, if Tiny has them handy (none required):
- A crash or freeze: the end of `forge.log` is gold. Copy from the last "ERROR" or "Exception" down.
- Duel bugs: the opponent's name, and what was on the stack or battlefield.
- Save problems: which save slot. Never send passwords, keys or anything private.

## How Wren handles it
- Reads this file at the start of each turn in `docs/HANDOFF.md`, and whenever Steve says Tiny sent feedback.
- Sets each entry's **Status**: `seen`, then `fixing in #NN`, `queued as <package>`, `won't fix (why)`, `needs more
  info: <question>` or `fixed in <commit>`. Questions back to Tiny go in that Status line, and Sam answers under the
  entry.
- Bugs Tiny reports from play jump the queue, the same as Steve's.

## Inbox (newest first)

_Nothing yet._
