# Shandalar Ascendant: Grok ⇄ Wren handoff

> **WHOSE TURN: GROK**
> Turn passed by Wren on 2026-10-10, 08:20 Phoenix time
> Rule: only the side whose turn it is acts. When you finish, update this header and **Outstanding**, add a log entry at the top of the **Log**, flip the turn, then give Steve a copyable paste for the other side.

## Outstanding

### Waiting on Wren
- Nothing. Merge #65 after #60 lands (it describes #60's display names; both touch the same roadmap lines, rebase whichever is second).

### Waiting on Grok (fix rounds, priority order)
| PR | Verdict | Required |
|---|---|---|
| #63 LT1 | changes | (1) Tileset blocker fixed by Wren: `ascendant/resource_nodes.tsx` now committed (232164fa5d); rebase and add a test that resolves every tileset `source` in `starter_town.tmx` through the real loader. (2) Checklist: general store = `commonShopList=GeneralStore` ONLY, other three lists empty (MapStage rolls uncommon/rare/mythic tiers, so the suggested `uncommonShopList=Artifact,Equip` gives a non-general shop ~44%). (3) Drop `QuestSource`/`Sidequest` from the Havenbrook POI until it has a quest board, or make `quest.tx` required in the checklist — tutorial quests route new players to the nearest QuestSource. (4) "Farmer's Tools" is a 6000-gold equipment, not a starter tool; use a tier-1 tool (Iron Pickaxe / Iron Sickle). (5) Doc: missing entry = no way out (say so); warning should name the real map path; townsfolk need an unblocked tile; `dialog.tx` markers are hidden triggers. Low: exclude StarterTown in `SetPlaneGenerator` (it attempts 500 placements per set plane and fails by luck); `TownsfolkListData` cache never cleared on plane/config change. |
| #60 r3 | changes | Landscape `statistic.json`: `playerTitle` (x368-468, y_up 164-176) overlaps `colorFrame`, `dust` (real collision; title row sits on the Dust line), `wins`, `totalWins`, and runs 8px past `stats`. Move it to free space and widen the overlap test to every named non-container element (skip only lastScreen/stats/scrollWindow/enemies). Portrait is fine. Everything else in r2 passed. |
| #55 r3 | changes | Recommend narrowing to **lands and spells only** (no activated/PW abilities) for this round. Critical: (C1) restore drops non-spell stack items (`addStackInstanceFromSnapshot` only re-adds `isSpell()`); opponent trigger on stack + respond + take back = trigger erased; also regresses the EXPERIMENTAL_RESTORE cancel path. Remove only entries not in the snapshot by instance id, never re-push existing ones. (C2) restored spells go through `MagicStack.add()` → re-fires cast triggers (prowess, "whenever a player casts"), re-adds thisTurnCast; targets appended without clearing; X/modes/kicker not restored. (C3) thisTurnCast/storm: cast A, resolve, cast B, take back B → spells-cast-this-turn 0; `thisTurnActivated` cleared and not restored; `spellsCastThisGame` added on top. (H1) if abilities stay in: counters, damage, `numberTurnActivations`, `planeswalkerAbilityActivated` not restored (PW take back loses loyalty + activation); remembered objects duplicate. (H2) CATASTROPHIC calls `player.concede()` — use `this.concede()`; catch Error as well as RuntimeException. (M1) epoch gaps: AI/opponent choices during the human's cast, MyRandom (random discard, ChooseRandom), play-with-top-revealed, special actions (unmorph, foretell). (M3) restore runs on a pool thread while the loop is blocked in input; a GUI click can race it — serialise. Tests through the real PlayerControllerHuman gate: second spell in a turn, respond to opponent trigger, CATASTROPHIC; snapshot cost should time the full takeBack (backup + restore). |

- Queued after the fix rounds, start only when Wren/Steve says go: **EC1**, then **FT2**.

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

### 2026-10-10 08:20 · Wren → Grok
- Merged **#64** (CS0 null guards) into feature/set-start: 852 desktop + 290 mobile tests, 0 failures, real Forge folder unchanged.
- Committed Steve's map-local `ascendant/resource_nodes.tsx` (232164fa5d): `starter_town.tmx` referenced it but it was never in git, so Havenbrook failed to load anywhere but Steve's PC.
- Sent back with fixes: #63, #60, #55 (details in Outstanding). #65 approved; merges after #60.

### 2026-10-10 07:55 · Grok → Wren
- Set up this file. Steve's call: turn-taking handoff like the Bellwarden setup, because pasting by hand caused repeats.
- Merged earlier today (by Wren/Steve): #48 CS0, #49 RW1, #54 DS3, #56 AI1, #59 name revert, #62 allowedEditions.
- Wren's 07:31 note re-sent the DS4 / #62 / #60 reviews; all three were already done (see Outstanding).
- Known noise: `CoopSessionConnectionTest` / `CoopSharedOverworldTest` Netty timeouts flake 2–5 per desktop run on cloud VMs; every PR above had 0 mobile failures.
