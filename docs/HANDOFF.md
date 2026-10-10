# Shandalar Ascendant: Grok ⇄ Wren handoff

> **WHOSE TURN: WREN**
> Turn passed by Grok on 2026-10-10, 12:40 Phoenix time
> Rule: only the side whose turn it is acts. When you finish, update this header and **Outstanding**, add a log entry at the top of the **Log**, flip the turn, then give Steve a copyable paste for the other side.

## Outstanding

### Waiting on Wren (review / test / merge; never while Steve is playing Forge)
| PR | Round | Head | What changed |
|---|---|---|---|
| #55 DS4 | r4 | `e8274087c58` | Each r4 blocker has a test that fails on r3 / with the fix reverted and passes now: **H-A** `abilityAfterSpellInvalidatesTakeBack` (PhaseHandler calls `game.invalidateTakeBack()` on a non-land/non-spell owner SA); **M3** `takeBackRestoreRunsOnGameLoopThreadAndRejectsClicks` (restore on the loop thread, clicks rejected during restore); **top-revealed** `playLandFromLibraryTopBumpsEpoch` (Courser of Kruphix; bump when a land/spell is played from the library); **random discard cost** `randomDiscardCostBumpsEpoch` (Sonic Burst, `HumanCostDecision`). Also: prowess test asserts stack + simultaneous entries empty and exact power; CATASTROPHIC test asserts input released; `pushForRestore` keeps order and fires no cast event; `CanTakeBack` javadoc = land/spell only. Grok's own check: `ProtocolMethod.takeBackLastAction` (inserted mid-enum) **dropped** with its `NetGameController` override — take back is SP-only and never on the wire (ProtocolMethod is serialized by name anyway). `TrackableProperty.CanTakeBack` is still the last constant. Timings, large board: `captureTakeBackSnapshot` avg 16 / max 30 ms; full takeBack avg 17 / max 22 ms. `TakeBackDs4Test` 12/12, mobile 310/0, desktop 864 with only the co-op Netty timeouts. |

### Waiting on Grok
- Nothing in flight. EC1, then FT2, queued until Steve says go. **DS5** (active effects in the card tooltip, Arena-style; roadmap) queued low priority after EC1.
- Later small follow-ups, not started: #63 doc line "lists must be truly empty, not a space" + tileset test also checking PNGs and `.tx` template tilesets; #60 test asserting `playerTitle` sits inside `stats`.

### Waiting on Steve (hands-on)
- Place the LT1 objects in `starter_town.tmx` in Tiled, on unblocked walkable tiles: Mira, Bren, Sela via `townsfolk.tx` (`townsfolkId=havenbrook_mira` / `_bren` / `_sela`); general store `shop.tx` with `commonShopList=GeneralStore` and uncommon/rare/mythic lists **empty** (truly empty, not a space). Town and NPC names are placeholders, rename freely.

## Standing rules (short)
- Each package = own branch + PR into `feature/set-start`. Fixes to an unmerged PR go on that PR; follow-ups after merge = new PR.
- Single-player build. Co-op gets crash/data-loss fixes only. Parked drafts: #27, #40, #45.
- Protocol: bump to base + 1 only when the PR changes the wire, right before review.
- Tests use the isolated temp user dir and never touch the real Forge folder. No test runs while Steve is playing Forge.
- Never edit `starter_town.tmx`. Never soften a quality check; fix the content.
- Bugs Steve reports from play jump the queue. So do Tiny's, which arrive via `docs/FEEDBACK.md` (written by Tiny's AI, Sam; Wren triages).

## Log (newest first)

### 2026-10-10 12:40 · Grok → Wren
- #55 r4 pushed (`5b6e315ef90`) with fail-before/pass-after tests for all four blockers, then `e8274087c58` dropping the mid-enum `ProtocolMethod.takeBackLastAction` (Grok caught it in the diff before handing back).
- Noted the #63/#60 nits as later follow-ups; not started.

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
