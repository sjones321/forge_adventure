# Adventure Starting Experience Plan

**Repo:** `sjones321/forge_adventure` (fork of Card-Forge/forge)  
**Branch base:** `cursor/forge-first-rebalance-c3dc` (first rebalance build already shipped)  
**Companion doc:** `docs/REBALANCE_PLAN.md`  
**Scope of this document:** Design only — no game code changes. Covers (1) choosing a starting era/set and building a non-OP starter, (2) draft events in the adventure world, (3) shops that sell booster packs, and (4) how that fits the earlier shop / NG+ / co-op plan.

---

## Plain-language summary (for Stephen)

Today, when you start a new Adventure, you mostly pick a **color** and a **mode**. In the default-feeling **Constructed** path you get a fixed pre-made deck (e.g. “Low Azorius”) that has little connection to Marvel, TMNT, Avatar, or current Standard. There *is* a **Standard** mode where you also pick a **set** (Jumpstart, Dominaria United, Lord of the Rings, Avatar, Marvel Super Heroes, …) and get a Jumpstart-style deck from that set — but it’s easy to miss, the set list is short, and Constructed ignores the set picker entirely.

Meanwhile, the game **already** knows how to:

- Filter which sets appear in shops and loot (`allowedEditions` / `restrictedEditions` on the plane).
- Generate real booster packs and sell them in shops.
- Run **draft, sealed, and Jumpstart events at inns** against AI, with an entry fee and prizes (including keeping your picks if you do well).

So the ask is less “invent limited from scratch” and more: **make the start feel like *your* set**, gate power carefully, and wire that choice into the world’s shops — reusing the limited and pack systems that already exist.

---

## 1. How things work today (with file paths)

### 1.1 New-game flow (what you see)

```
Settings (pick plane, default Shandalar)
  → StartScene → NewGame
    → NewGameScene (name, race, gender, avatar, difficulty, mode, color/deck, [starter edition])
      → WorldSave.generateNewWorld(...)
        → Config.starterDeck(...)
        → AdventurePlayer.create(starterDeck)
            deck slot 0 = starter
            collection = every card from that starter
```

| Piece | Path |
|-------|------|
| Main menu → New Game | `forge-gui-mobile/.../scene/StartScene.java` |
| Character / mode UI | `.../scene/NewGameScene.java` |
| UI layout | `forge-gui/res/adventure/common/ui/new_game.json` |
| Deck resolution | `.../util/Config.java` → `starterDeck(...)` |
| World + player create | `.../world/WorldSave.java` → `generateNewWorld(...)` |
| Player storage | `.../player/AdventurePlayer.java` → `create(...)` |
| Modes | `.../util/AdventureModes.java` |

**Modes:** `Standard`, `Constructed`, `Chaos`, `Pile`, `Custom`, `Commander`, `Precon`, `CommanderPrecon`.

**Starter edition picker** shows only for modes where `usesStarterEditionSelector()` is true: **Standard**, **Precon**, **CommanderPrecon**. Constructed (often the default preference) **hides** the edition control.

**Configured starter editions today** (`common/config.json`): JMP, DMU, BRO, J22, ONE, MOM, LTR, J25, **TLA**, **MSH**, “(All)”.  
Not on that list yet: **SPM** (Spider-Man), **TMT** (TMNT), full “current Standard” as a format bundle.

### 1.2 How the starter deck is built

| Mode | What you get | Source |
|------|----------------|--------|
| **Constructed** | Fixed `.dck` by color (e.g. Low Azorius / Rakdos / …) | `difficultyData.constructedStarterDecks` |
| **Standard** | Jumpstart-style deck from `.json` (`jumpstartPacks`), filtered by chosen edition | `difficultyData.starterDecks` + optional unused `starterDecksByEdition` |
| Chaos / Custom / Pile / Commander / Precon | Random / custom / pile / commander / folder precon | respective maps / folders |

Hook already coded but **unused in JSON:** `ConfigData.starterDecksByEdition` — map of set code → color → deck path. `Config.starterDeck` checks it for Standard before falling back to Jumpstart JSON.

Example Standard JSON (`forge-gui/res/adventure/common/decks/starter/white_n.json`):

```json
{ "name":"White", "jumpstartPacks":["white","white","green"] }
```

On create, starter cards are copied into both **deck[0]** and the **collection** (`AdventurePlayer.create`).

### 1.3 Card pool filtering by edition (shops / loot / packs)

Master adventure pool: `RewardData.initializeAllCards()` in `forge-gui-mobile/.../data/RewardData.java`.

Filter order:

1. `legalCards` reward filter (whitelist), else  
2. `allowedEditions` (whitelist), else  
3. `restrictedEditions` (blacklist), else  
4. all obtainable cards  

Plus plane `restrictedCards` by name, Alchemy/ante filters, etc.

| Plane | Pool style |
|-------|------------|
| **Shandalar** (uses `common/config.json`) | Open pool minus Un-sets / joke editions |
| **Innistrad** | Tight `allowedEditions`: ISD, DKA, SOI, MID, YMID |
| **Crystal Kingdoms** | Final Fantasy–gated (`FIN` / related) |
| **Realm of Legends** | Own config; already has many set-themed shops |

**Important:** your **starting edition does not currently change** the world’s allowed shop/loot pool. Starting era and world geography are disconnected today.

Docs: `docs/Adventure/Configure-Sets.md`, `docs/REBALANCE_PLAN.md` §1.

### 1.4 Boosters and pack shops

| Layer | Path / API |
|-------|------------|
| Core pack open | `forge-core/.../BoosterGenerator.java`, `UnOpenedProduct` |
| Adventure generate | `AdventureEventController.generateBooster(setCode)`, `generateBoosterByColor` |
| Alternate | `CardUtil.generateBoosterPackAsDeck(...)` |
| Overlay templates | `AdventureOverrides.getBoosterTemplate` |
| Shop reward type | `RewardData` → `"cardPackShop"` / `"cardPack"` → `Reward.Type.CardPack` |
| Inventory | `AdventurePlayer.boostersOwned`; open in `InventoryScene.openBooster()` |
| Pricing | `CardUtil.getBoosterPrice` |

Packs are stored as **Decks** (cards in main; `comment` = set code), not as separate item types.

**Shops already sell packs:** e.g. Shandalar `BoosterPackShop` / color booster shops (`type: "cardPackShop"`). **Realm of Legends** already has set singles shops (`SPMShop`, `TMTShop`, `TLAShop`, `MSHShop`, …) and many shops that sell packs pinned with `"editions": ["BLB"]` etc. Pattern for “singles of a set + packs of that set” is proven in data.

### 1.5 Draft / sealed / events already in Adventure

This is the big surprise relative to “we need draft in adventure”:

| Piece | Path |
|-------|------|
| Formats | `AdventureEventController.EventFormat`: Draft, Sealed, Jumpstart, Constructed |
| Create random town event | `AdventureEventController.createEvent(pointID)` |
| Event model | `AdventureEventData` (participants, rounds, sealed pool, draft, rewards) |
| Enter via **Inn** | `InnScene` → `MapStage` object type `"inn"` |
| UI / matches | `EventScene` |
| Draft UI | `AdventureDeckEditor` (`AdventureDraftPackPage`, `completeDraft`) |
| Shared draft engine | `forge-gui/.../limited/BoosterDraft.java` + `LimitedPlayerAI` |
| Desktop sealed parallel | `SealedCardPoolGenerator` (desktop); adventure uses `generateSealedPool()` |

**Player-facing loop today:** enter an inn → optional event available (cooldown by date) → early game may roll Jumpstart; else mostly Draft, sometimes Sealed → pay entry (Draft base ~3000 gold / 50 shards; Sealed higher) → draft or open sealed pool against AI → play bracket/round-robin → prizes by wins; **3 wins** awards the drafted deck / sealed pool as a `CardPack` reward.

**Gaps:** mid-draft state is `transient` (save mid-pack is fragile); AI–AI event matches are coin-flip; Constructed event format exists but isn’t the random inn roll; enemy/quest JSON rarely drop packs.

**Arena** (`ArenaScene`) is a separate **constructed** bracket vs town enemies — not limited.

### 1.6 Do the sets Stephen cares about exist?

Edition files live in `forge-gui/res/editions/*.txt`. Current Standard allowlist: `forge-gui/res/formats/Sanctioned/Standard.txt`.

| Ask | Codes in Forge | Boosters? | Notes |
|-----|----------------|-----------|-------|
| Marvel Spider-Man | **SPM** (+ SPE, MAR, …) | SPM yes | In current Standard |
| Marvel Super Heroes | **MSH** (+ MSC) | MSH yes | Already in `starterEditions` |
| TMNT | **TMT** (not `TMNT`) | yes | In Standard; Realm `TMTShop` |
| Avatar | **TLA** (not `ATLA`) | yes | Already in `starterEditions`; Realm `TLAShop` |
| Current Standard | WOE…FRA incl. SPM, TLA, TMT, MSH, FIN, HOB, … | mostly yes | Use `FModel.getFormats().getStandard()` |
| 40K / AFR / LTR | **40K**, **AFR**/CLB, **LTR** | 40K no; AFR/LTR yes | Already used in adventure shops / starters |

Draft blocks for SPM, TLA, TMT, MSH, AFR, LTR exist under `forge-gui/res/blockdata/blocks.txt`.

---

## 2. Design goals (Stephen)

1. **Connection:** Start by picking an era / format / set you care about (Standard, Marvel, TMNT, Avatar, …).
2. **Agency:** More say in what goes into the opening deck/pool than a opaque prebuilt.
3. **Not broken:** Opening should not dump mythics / Power / full Standard constructed into the collection.
4. **World consistency:** Chosen start should bias which sets towns sell (and later NG+ unlocks), per `REBALANCE_PLAN.md`.
5. **Reuse:** Prefer existing pack generation, inn events, and shop JSON over new engines.

---

## 3. Starting experience — three concrete options

### Option A — “Pick set + curated archetype” (expand what Standard almost does)

**Player flow:** Pick a **theme** (Current Standard / Marvel / TMNT / Avatar / LotR / …) → pick a **color or named archetype** within that theme → receive a tuned starter deck (and matching small collection).

**Implementation shape:**

- Expand `starterEditions` / names (DATA) to include SPM, TMT, and a synthetic **Standard** choice.
- Populate **`starterDecksByEdition`** (already wired in Java, unused) with per-set color/archetype decks, **or** generate Jumpstart from the chosen set’s special boosters (existing Standard path).
- For “Current Standard,” either: (a) Jumpstart from a flagship Standard set, or (b) a small pool of legal Standard commons/uncommons only for starters.
- Power limits: rarity caps in deck files; adventure `restrictedCards`; no mythic-heavy constructed stubs; optional ban list per theme.

| Pros | Cons |
|------|------|
| Smallest change; Java hook already exists | Less “I built this” feel than sealed/draft |
| Easy to balance (authors control lists) | Content work: decks per set × color |
| Clear brand connection | “Standard” as one picker needs a defined product |

**Data vs Java:** Mostly **DATA** (config + decks). Java only if adding a “format bundle” picker or Standard-filter helper.

---

### Option B — “Sealed opening” (open N packs, build a deck)

**Player flow:** Pick a set (or Standard = packs from N legal Standard sets) → open **N boosters** (recommend **4–6**, not 6+9 competitive sealed) → build a **40-card** deck in the adventure deck editor → leftover cards stay in collection (or sideboard → collection).

**Implementation shape:**

- Reuse `AdventureEventData.generateSealedPool()` / `AdventureEventController.generateBooster` / `UnOpenedProduct`.
- New new-game branch in `NewGameScene` / `WorldSave` (or a short post-create scene) that opens packs then jumps to `AdventureDeckEditor` in sealed-build mode.
- Power limits: pack count; rarity natural to boosters; optional strip of chase slots; ban list; don’t add extra rares outside packs.

| Pros | Cons |
|------|------|
| Strong agency and set connection | More UI/flow Java than Option A |
| Reuses real booster math | Variance: some starts stronger (mitigate with N and bans) |
| Matches Stephen’s sealed idea directly | Onboarding time longer than picking a color |

**Data vs Java:** **JAVA** for new-game sealed flow + editor entry; **DATA** for pack counts / allowed sets / bans.

---

### Option C — “Starter draft vs AI”

**Player flow:** Pick a set → draft a pod against AI (reuse `BoosterDraft` + adventure draft UI) → optional short practice matches → keep picks as starting deck/collection.

**Implementation shape:**

- Reuse inn draft path (`AdventureDeckEditor` draft pages, `LimitedPlayerAI`) without requiring gold entry fee at new game.
- Shorter product: e.g. **3 packs** drafted, not full 3×8 competitive draft, **or** full draft but skip the tournament until later inns.

| Pros | Cons |
|------|------|
| Maximum agency; teaches draft for later town events | Longest start; mid-draft save already fragile |
| Full reuse of existing draft stack | Balance: drafted rares enter collection immediately |
| Great marketing beat (“draft your adventure”) | Worst first-session drop-off risk for a casual player |

**Data vs Java:** Mostly **JAVA** glue + tuning; pack templates already **DATA**.

---

### Recommendation

**Ship Option A first, with Option B as the “advanced start” toggle on the same screen.**

Reasons:

1. A is the **smallest playable change** and already half-implemented (`starterDecksByEdition`, edition selector, Jumpstart JSON, TLA/MSH in list).
2. B delivers the sealed fantasy Stephen described without forcing a full draft before you’ve seen the overworld.
3. C is excellent **later** (or as a third mode) once inn events are surfaced in onboarding — full draft-at-start is a lot of friction for someone who’s only played a few minutes.
4. Power control is easiest with A (authored decks) and still good with B (pack count + bans); C needs the strictest caps.

Concrete default product:

- New-game **Theme** step: Current Standard | Marvel (SPM/MSH) | TMNT (TMT) | Avatar (TLA) | LotR (LTR) | Classic Jumpstart | …  
- Under Theme: **Quick start** = archetypes (A) **or** **Sealed start** = 5 packs from that theme (B).  
- Hide or deprecate opaque Constructed `.dck` as the default for new players on this fork (keep available as “Classic Constructed” for veterans).

---

## 4. Power limits (apply to all options)

| Lever | How |
|-------|-----|
| Pack count | Sealed start: 4–6 packs; not full competitive sealed |
| Rarity | Archetype decks: commons/uncommons + few uncommons/rares; no mythic piles |
| Banned list | Extend plane `restrictedCards` / theme-specific bans (tutors, fast mana already partly listed in `common/config.json`) |
| Collection injection | Starter cards enter collection (today’s behavior) — so **deck power = collection power**; never seed extra sealed boxes into inventory at minute zero |
| Format filter | Standard theme: only Standard-legal printings via `GameFormat.getStandard()` |
| Gold | Keep Easy starting gold modest (rebalance already nerfed Easy loot); don’t gift event-entry money |

---

## 5. Draft events in the world (design)

### 5.1 What to keep

Keep the **Inn event** system as the primary limited loop. It already matches the desired fantasy: pay entry → draft/sealed vs AI → play rounds → keep picks / win prizes.

### 5.2 What to change (design, not yet code)

1. **Discoverability:** Tutorial / first-town inn tooltip: “Draft and sealed events happen here.” Many players will never notice inns offer limited.
2. **Geography:** Prefer event **card blocks** drawn from the player’s **starting theme** and nearby town set identity (see §7), intersecting `restrictedEvents` / allowed editions. Today `pickCardBlockByFormat` is mostly random among legal blocks.
3. **Entry fee vs exploration:** Fees are high (Draft ~3000g). After Easy gold nerf, ensure early inns offer Jumpstart / cheaper bronze events so new players can play limited before farming.
4. **Set-town draft halls (optional Phase 2):** POIs that always offer a fixed block (e.g. TMT draft hall) instead of random — data + `createEvent(format, cardBlock, pointID)` already supports forced block.
5. **Persistence:** Fix mid-draft save before advertising long drafts heavily (`draft` field is transient on `AdventureEventData`).
6. **Prizes:** Keeping the deck at 3 wins is fine; consider a lesser “keep commons/uncommons only” consolation so failing a draft isn’t total loss — product choice for Stephen.

### 5.3 Reuse map

| Need | Reuse |
|------|--------|
| AI drafting | `LimitedPlayerAI` / `BoosterDraft` |
| Pack generation | `AdventureEventController.generateBooster` |
| Sealed pools | `AdventureEventData.generateSealedPool` |
| Matches | `EventScene` + `DuelScene` |
| Keep picks | `rewardDeck` → `CardPack` reward |

Avoid rebuilding desktop sealed UI inside adventure; wrap what exists.

---

## 6. Pack shops (design)

### 6.1 Target feel

Most **regular set shops** sell:

- A **few singles** from that set (rarity mix, finite stock — aligns with rebalance E1/E2), and  
- **1–3 booster packs** of that set (`cardPackShop` + `editions: ["SET"]`).

### 6.2 Already true / nearly true

- Generic pack shops exist (Shandalar).  
- Set singles shops exist (Realm of Legends SPM/TMT/TLA/MSH; Shandalar 40K/DnD patterns).  
- Set-pinned pack rewards exist (Realm pack shelves).

### 6.3 Proposed content pattern (DATA)

```json
{
  "name": "TMTShop",
  "description": "Cards in a Half-Shell",
  "rewards": [
    { "count": 4, "rarity": ["rare", "mythic rare"], "editions": ["TMT"] },
    { "count": 4, "rarity": ["common", "uncommon"], "editions": ["TMT"] },
    { "type": "cardPackShop", "count": 2, "editions": ["TMT"] }
  ]
}
```

Wire those shops onto towns (map `shopList` / rarity lists) instead of mystery/rotating themes — consistent with `REBALANCE_PLAN` Phase B.

**Discount bundle shops** (rebalance E6): rare POIs selling multi-pack bundles or fixed lots at `priceModifier < 1` — exploration bait, not main-street restock.

**Java needed?** Optional `ShopData.priceModifier` (already proposed in rebalance). Pack generation itself is done.

---

## 7. Fit with the earlier rebalance plan

| Earlier idea | How this doc plugs in |
|--------------|------------------------|
| **E1** restock/rotate off | Done — pack shelves stay finite; no shard reroll into mythics |
| **E2** set-specialized shops | Starting **theme** = seed for which set shops appear in gen 0 geography |
| **E3** Easy gold nerf | Done — keeps sealed/draft entry meaningful |
| **E5** NG+ set unlocks | Gen 0 = starter theme (+ small neighbors); NG+ adds other themes/Standard slices |
| **E6** discount bundles | Pack bundles + clearance lots as exploration rewards |
| **A1** Adventure.ai | Done — limited events still use duel AI |
| **C1/C2** 2HG co-op later | Keep **one party wallet**; event entry and pack purchases stay shared; don’t per-player pack inventories |

**Starting era → world shops (recommended rule):**

1. On new game, store `startingTheme` / `unlockedSets` on the player (same fields E5 will need).  
2. Gen 0 shop/booster generation intersects plane allow-list with `unlockedSets`.  
3. Town assignment: capital / nearby towns heavily weight the starter theme; distant biomes tease locked sets (boarded up or empty — open question).  
4. Inn draft blocks prefer unlocked sets.

This makes “I started with TMNT” mean something after the first town, not only on the new-game screen.

---

## 8. Data vs Java checklist

| Work | Type | Notes |
|------|------|-------|
| Expand `starterEditions` / names (SPM, TMT, Standard label) | **DATA** | `common/config.json` |
| Author `starterDecksByEdition` or per-set Jumpstart JSON | **DATA** | Decks under `res/adventure/.../decks/starter/` |
| Theme → set-code tables / NG+ unlock tiers | **DATA** (+ light Java load) | Plane config |
| Set shops = singles + packs; town wiring | **DATA** | `shops.json` + maps |
| Bundle `priceModifier` | **JAVA** + DATA | From rebalance E6 |
| Persist `startingTheme` / `unlockedSets` | **JAVA** | `AdventurePlayer` save |
| Filter pool/shops/events by unlocks | **JAVA** | `RewardData.initializeAllCards`, `CardUtil` boosters, event block pick |
| Sealed / draft **new-game** modes | **JAVA** | `NewGameScene`, `WorldSave`, editor entry |
| Inn discoverability / cheaper early events | **DATA**/light Java | Fees, tutorial strings, forced Jumpstart weight |
| Mid-draft persistence | **JAVA** | `AdventureEventData` |
| True 2HG | **Later JAVA** | Out of scope here |

---

## 9. Phased build order (smallest playable change first)

### Phase S0 — Docs / product decisions (this PR)

Agree themes list, default mode (Quick vs Sealed), pack count, whether Constructed stays default.

### Phase S1 — Theme picker + archetype starters (playable vertical slice)

1. Add SPM, TMT (and any missing favorites) to `starterEditions`.  
2. Fill `starterDecksByEdition` for 2–3 themes × 5 colors (or Jumpstart-only for those codes).  
3. Make **Standard mode + edition picker the recommended default** on this fork (or rename UI to “Theme”).  
4. Playtest: starts feel on-theme; not mythic soup.

**Impact:** Connection problem solved with almost no new systems.  
**Risk:** Low.

### Phase S2 — Starter theme gates gen-0 shops/packs

1. Save starting set codes on the player.  
2. Intersect adventure pool / `cardPackShop` / inn blocks with unlocked sets.  
3. Point a few Shandalar (or Realm) towns at matching set shops **with packs on the shelf**.

**Impact:** World matches the opening fantasy; advances rebalance E2/E5.  
**Risk:** Medium (empty shops if maps not wired; save field migration).

### Phase S3 — Optional sealed start

1. New-game “Sealed opening”: N packs → build 40.  
2. Power rules from §4.  
3. Reuse `generateSealedPool` helpers.

**Impact:** Agency Stephen asked for.  
**Risk:** Medium (UX length, variance).

### Phase S4 — Inn limited polish

1. Onboarding copy; early cheaper Jumpstart.  
2. Prefer unlocked blocks.  
3. Optional fixed set draft halls.  
4. Mid-draft save fix.

**Impact:** Draft-in-adventure becomes a reliable pillar.  
**Risk:** Medium (save fix).

### Phase S5 — Starter draft mode (optional)

Only if sealed + inns aren’t enough agency. Full AI draft at new game.

### Phase S6 — Align with remaining rebalance

E4 gold scaling, E6 bundles, NG+ generations adding themes, then co-op docs — do not block S1–S3 on 2HG.

---

## 10. Open questions for Stephen (plain language)

1. **Quick start or sealed first?** Are you happy starting with “pick Avatar → pick Blue archetype deck,” or do you want “open five Avatar packs and build” as the *default* from day one?  
2. **What is “Standard” as a start?** One flagship set, a rotation of current Standard sets, or a sealed pool mixed from several Standard sets?  
3. **Marvel: SPM, MSH, or both?** Spider-Man and Marvel Super Heroes are separate products in Forge.  
4. **Should your starting theme lock the whole world’s shops?** Strict (only that theme until NG+) vs soft (theme common nearby; other sets rare/distant)?  
5. **Constructed pre-made decks:** Keep as an advanced/classic option, or remove from the default path on this fork?  
6. **Draft at inns:** Did you know these already exist? Want them cheaper/earlier, more obvious, or tied to specific towns?  
7. **When you lose a draft event:** Keep nothing, keep a few cards, or always keep the pool but only “register” the deck for prizes on wins?  
8. **Pack count for sealed start:** 4, 5, or 6?  
9. **Which plane first?** Shandalar (open sandbox) vs Realm of Legends (already has UB set shops) vs Innistrad (already tightly gated)?  
10. **Commander / precon starts:** In scope for theme picking, or focus on 60-card adventure for now?

---

## 11. Suggested backlog IDs (extend rebalance table)

| ID | Item | Depends | Type |
|----|------|---------|------|
| **S1** | Theme/edition list + archetype/`starterDecksByEdition` | — | Data |
| **S2** | Persist starting unlocks; filter shops/packs/events | S1, E2 | Java/Data |
| **S3** | Sealed new-game opening | S1 | Java |
| **S4** | Inn limited UX + early fees + block bias | S2 | Data/Java |
| **S5** | Optional starter draft mode | S4 | Java |
| **S6** | Mid-draft persistence | S4 | Java |

Do **S1** before more AI or 2HG work; it unblocks emotional buy-in for the whole rebalance track.

---

## Appendix A — Key file index

| Topic | Paths |
|-------|-------|
| Rebalance companion | `docs/REBALANCE_PLAN.md` |
| New game UI | `NewGameScene.java`, `new_game.json` |
| Starter resolution | `Config.starterDeck`, `WorldSave.generateNewWorld` |
| Pool filters | `RewardData.initializeAllCards`, plane `config.json` |
| Pack shops | `shops.json` (`cardPackShop`), `RewardData`, `AdventurePlayer.boostersOwned` |
| Events | `AdventureEventController`, `AdventureEventData`, `InnScene`, `EventScene` |
| Limited core | `BoosterDraft`, `LimitedPlayerAI`, `BoosterGenerator` |
| Editions / Standard | `res/editions/*.txt`, `res/formats/Sanctioned/Standard.txt`, `res/blockdata/blocks.txt` |

## Appendix B — Relationship to first rebalance build

Already shipped on `cursor/forge-first-rebalance-c3dc`: shop restock/rotate off, Easy gold nerf, `Adventure.ai`. This starting-experience plan **does not alter that code**; it designs the next player-facing layer so set identity at minute zero matches set geography and limited play later.
