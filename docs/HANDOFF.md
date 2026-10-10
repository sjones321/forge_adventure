# Shandalar Ascendant: Grok ⇄ Wren handoff

> **WHOSE TURN: WREN**
> Turn passed by Grok on 2026-10-10, 07:55 Phoenix time
> Rule: only the side whose turn it is acts. When you finish, update this header and **Outstanding**, add a log entry at the top of the **Log**, flip the turn, then give Steve a copyable paste for the other side.

## Outstanding

### Waiting on Wren (review / test / merge on Steve's PC, never while Steve is playing Forge)
| PR | What | State Grok left it in |
|---|---|---|
| #55 | DS4 take back, narrower design (round 2) | `828396a600d`, clean. Dedicated pre-action snapshot, info-epoch lock, full stack revert, previousGameState reset; lands + spells + non-mana abilities; snapshot cost ~11 ms avg / 16 ms max on an 80-creature board |
| #60 | Shandalar Standard / Completionist rename + title picker (round 2) | `526c905`, clean. Own `playerTitle` label in both Status layouts + overlap test, focus kept on equip, titles above achievements, old-save tests |
| #63 | LT1 starter town (Havenbrook) | `8b8f11cd40f`, clean. `starter_town.tmx` NOT touched. Steve's Tiled checklist in `docs/Adventure/LT1-StarterTown.md` |
| #64 | CS0 follow-up: null guards + stale-cache test warm-up | `ea5a8336fab`, clean |
| #65 | Docs/comments: Bellwarden → Shandalar Ascendant (outside `docs/Bellwarden/`) | clean |

### Waiting on Grok
- Nothing in flight.
- Queued, start only when Wren/Steve says go: **EC1** (game-based card economy), then **FT2** (fortress tiers and structures).

### Waiting on Steve (hands-on)
- Place the LT1 objects in `starter_town.tmx` in Tiled (Mira, Bren, Sela via `townsfolk.tx`; general store `shop.tx` with `commonShopList=GeneralStore`). Town and NPC names are placeholders, rename freely.

## Standing rules (short)
- Each package = own branch + PR into `feature/set-start`. Fixes to an unmerged PR go on that PR; follow-ups after merge = new PR.
- Single-player build. Co-op gets crash/data-loss fixes only. Parked drafts: #27, #40, #45.
- Protocol: bump to base + 1 only when the PR changes the wire, right before review.
- Tests use the isolated temp user dir and never touch the real Forge folder. No test runs while Steve is playing Forge.
- Never edit `starter_town.tmx`. Never soften a quality check; fix the content.
- Bugs Steve reports from play jump the queue.

## Log (newest first)

### 2026-10-10 07:55 · Grok → Wren
- Set up this file. Steve's call: turn-taking handoff like the Bellwarden setup, because pasting by hand caused repeats.
- Merged earlier today (by Wren/Steve): #48 CS0, #49 RW1, #54 DS3, #56 AI1, #59 name revert, #62 allowedEditions.
- Wren's 07:31 note re-sent the DS4 / #62 / #60 reviews; all three were already done (see Outstanding).
- Known noise: `CoopSessionConnectionTest` / `CoopSharedOverworldTest` Netty timeouts flake 2–5 per desktop run on cloud VMs; every PR above had 0 mobile failures.
