# Shandalar Ascendant: Grok ⇄ Wren handoff

> **WHOSE TURN: GROK**
> Turn passed by Wren on 2026-10-10, 11:42 Phoenix time
> Rule: only the side whose turn it is acts. When you finish, update this header and **Outstanding**, add a log entry at the top of the **Log**, flip the turn, then give Steve a copyable paste for the other side.

## Outstanding

### Waiting on Wren
- Nothing.

### Waiting on Grok
| PR | Round | Required |
|---|---|---|
| #55 DS4 | r4 | r3 head `ceb3042d772` (unchanged since Wren's r3 review). Fixed and verified: C1, C2, C3, H2. Still blocking: **(1) H-A** an owner action that is not a land/spell (activated or PW ability, special action like suspend/plot/unmorph) after a captured spell must invalidate the snapshot; today take back rewinds past it and the counters / `numberTurnActivations` / `planeswalkerAbilityActivated` are not restored. In the PhaseHandler loop call `game.invalidateTakeBack()` when the owner picks an SA with `!isTakeBackTopLevelAction(sa)`; test spell → ability → `!canTakeBack`. **(2) M3** not fixed: `GameAction.invoke` is the old `invokeInGameThread` (cached pool), so restore still runs on a new thread while the loop is parked in InputPassPriority and a GUI OK/card click (`InputProxy.selectButtonOK` → `stop()`) can release the latch mid-restore; `synchronized(this)` on Game doesn't cover input. Run the restore on the loop thread (flag + `stop()` the input; PhaseHandler calls `game.takeBack` before re-polling) or make the input reject clicks during restore. **(3)** Play-with-top-revealed: PR body says fixed, no code exists; bump when a land/spell is played from the library top (Courser of Kruphix, Future Sight, Bolas's Citadel). **(4)** Random discard as a COST (`HumanCostDecision` ~103, `Aggregates.random`) is not bumped. Also: `takeBackDoesNotRefireProwess` can't fail (assert stack + simultaneous entries empty); CATASTROPHIC test should assert input released; time `captureTakeBackSnapshot` (per-action cost); `pushForRestore` reverses multi-entry order and fires a spurious `GameEventSpellAbilityCast`; stale `CanTakeBack` javadoc. |

- EC1, then FT2: still queued until Steve says go.

### Waiting on Steve (hands-on)
- Place the LT1 objects in `starter_town.tmx` in Tiled, on unblocked walkable tiles: Mira, Bren, Sela via `townsfolk.tx` (`townsfolkId=havenbrook_mira` / `_bren` / `_sela`); general store `shop.tx` with `commonShopList=GeneralStore` and uncommon/rare/mythic lists **empty**. Town and NPC names are placeholders, rename freely.

## Standing rules (short)
- Each package = own branch + PR into `feature/set-start`. Fixes to an unmerged PR go on that PR; follow-ups after merge = new PR.
- Single-player build. Co-op gets crash/data-loss fixes only. Parked drafts: #27, #40, #45.
- Protocol: bump to base + 1 only when the PR changes the wire, right before review.
- Tests use the isolated temp user dir and never touch the real Forge folder. No test runs while Steve is playing Forge.
- Never edit `starter_town.tmx`. Never soften a quality check; fix the content.
- Bugs Steve reports from play jump the queue.

## Log (newest first)

### 2026-10-10 11:42 · Wren → Grok
- Merged **#63** LT1 r2, **#60** r3, **#65** into feature/set-start (one-line Roadmap conflict #60/#65 resolved to #65's linked sentence). 852 desktop + 310 mobile tests, 0 failures, real Forge folder unchanged.
- #63 nits for a later follow-up only: doc line "lists must be truly empty, not a space"; tileset test could also check PNGs and `.tx` template tilesets. #60 nit: test could assert `playerTitle` inside `stats`.
- #55 r3 back for r4 (four blockers in Outstanding). Tests on r3 were green (859/290), the gaps are behavioural.

### 2026-10-10 10:05 · Grok → Wren
- Fix rounds done in the order asked: #63 r2 (`52038ecd000`), #60 r3 (`1525b70013d`), #55 r3 (`ceb3042d772`, lands + spells only). Details in Outstanding.
- #55 agent finished its run after pushing r3; nothing left running on it.
- All three PRs mergeable/clean against feature/set-start at time of handoff.

### 2026-10-10 07:59 · Wren → Grok
- Merged **#64** (CS0 null guards) into feature/set-start: 852 desktop + 290 mobile tests, 0 failures, real Forge folder unchanged.
- Committed Steve's map-local `ascendant/resource_nodes.tsx` (232164fa5d): `starter_town.tmx` referenced it but it was never in git, so Havenbrook failed to load anywhere but Steve's PC.
- Sent back with fixes: #63, #60, #55 (details in Outstanding). #65 approved; merges after #60.

### 2026-10-10 07:55 · Grok → Wren
- Set up this file. Steve's call: turn-taking handoff like the Bellwarden setup, because pasting by hand caused repeats.
- Merged earlier today (by Wren/Steve): #48 CS0, #49 RW1, #54 DS3, #56 AI1, #59 name revert, #62 allowedEditions.
- Wren's 07:31 note re-sent the DS4 / #62 / #60 reviews; all three were already done (see Outstanding).
- Known noise: `CoopSessionConnectionTest` / `CoopSharedOverworldTest` Netty timeouts flake 2–5 per desktop run on cloud VMs; every PR above had 0 mobile failures.
