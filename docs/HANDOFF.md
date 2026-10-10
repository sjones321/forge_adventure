# Shandalar Ascendant: Grok ⇄ Wren handoff

> **WHOSE TURN: WREN**
> Turn passed by Grok on 2026-10-10, 10:05 Phoenix time
> Rule: only the side whose turn it is acts. When you finish, update this header and **Outstanding**, add a log entry at the top of the **Log**, flip the turn, then give Steve a copyable paste for the other side.

## Outstanding

### Waiting on Wren (review / test / merge; never while Steve is playing Forge)
| PR | Round | Head | What changed |
|---|---|---|---|
| #63 LT1 | r2 | `52038ecd000` | Merged base (with `resource_nodes.tsx`); `TemplateTmxMapLoader.resolveExternalTilesets` + real-loader test that fails on a missing `.tsx`; checklist and doc say general store = `commonShopList=GeneralStore` only (other three empty); `QuestSource`/`Sidequest` dropped from Havenbrook POI; GeneralStore uses Iron Pickaxe / Iron Sickle; doc: missing entry = no way out, warning names the real map path, townsfolk on unblocked tiles, `dialog.tx` = hidden triggers; lows: `SetPlaneGenerator.excludeHomeOnlyPois`, townsfolk cache cleared on config/plane change. `starter_town.tmx` not in diff. Mobile 306/0. |
| #60 | r3 | `1525b70013d` | Landscape `playerTitle` moved to x 310–460, y_up 146–158 (inside `stats`, clear of dust/wins/totalWins/colorFrame). Overlap test is now full pairwise over named non-container elements in both layouts (skips lastScreen/stats/scrollWindow/enemies; only allowed pair avatar+colorFrame), plus a regression asserting the r2 box hit `dust`. Portrait unchanged. Mobile 300/0. |
| #55 DS4 | r3 | `ceb3042d772` | Take back = **land plays and spells only**; no ability path. Stack restore by instance id (only removes entries not in the snapshot, never re-pushes); no cast-trigger re-fire; exact thisTurnCast/storm/spellsCastThisGame restore; CATASTROPHIC uses `this.concede()` and catches Error; epoch bumps for opponent choices mid-cast, MyRandom, top-revealed, special actions; restore serialised. `TakeBackDs4Test` 7/7 through the real gate. Full takeBack (backup + restore) on large board: avg 34 ms, max 44 ms. Mobile 290/0. |
| #65 | — | `e4aa220efdf` | Approved; merge after #60. |

Desktop on all three: only `CoopSessionConnectionTest` / `CoopSharedOverworldTest` Netty timeouts (4 or fewer per run); everything else green.

### Waiting on Grok
- Nothing in flight. EC1, then FT2, queued until Wren/Steve say go.

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
