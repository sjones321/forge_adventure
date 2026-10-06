# Forge Adventure / AI Rebalance Plan

**Repo:** `sjones321/forge_adventure` (fork of Card-Forge/forge)  
**Scope:** Investigation and design only — no game code changes in this PR.  
**Goals (Stephen):** (1) Adventure economy/shops that reward exploration over grind, with set progression via NG+; (2) less linear match AI; (3) keep a path open for later co-op Adventure as true Two-Headed Giant.

---

## Executive summary

Adventure shops already support set/color filters, restock disable, and booster shops via JSON. The grind loop is real: **any restockable shop can be rerolled with shards**, rotating shops change daily, gold from weak enemies has **no relative-difficulty or diminishing-returns scaling**, and Easy difficulty **boosts** loot variance (`rewardMaxFactor: 1.5`). NG+ exists but only regenerates the world and sets a character flag — it does **not** unlock sets.

The match AI is heuristic-driven with optional shallow simulation. It already has attack/block restraint knobs and Main2 mana reservation, but **no “hold mana for a counterspell” reservation**. Full/hybrid simulation is a lobby AI option, not an `.ai` profile flag, and the simulator explicitly TODOs open-mana-for-permission scoring.

**Recommended order:** data-first shop/economy killswitches → gold anti-farm → NG+ set unlocks → AI profile tweaks → targeted AI code (counter mana, bad attacks) → 2HG later.

---

## 1. Adventure economy & shops

### 1.1 Architecture map

```
Plane config (allowed/restricted editions)
  └─ forge-gui/res/adventure/{plane}/config.json  (or common/config.json)
     └─ ConfigData / Config / AdventureOverrides
        └─ RewardData.initializeAllCards()   // master adventure pool

shops.json  ──► WorldData.getShopList() ──► ShopData
enemies.json ──► EnemyData.rewards[] ──► RewardData.generate()

Map (.tmx) shop / Rotating objects
  └─ MapStage.loadObjects()  ("Rotating" | "shop")
     └─ ShopActor(rewards, ShopData)
        └─ RewardScene (buy / restock UI)
           └─ PointOfInterestChanges (seeds, cardsBought, price mods)
```

| Role | Path |
|------|------|
| Shop schema | `forge-gui-mobile/src/forge/adventure/data/ShopData.java` |
| Reward schema + generation | `.../data/RewardData.java` |
| Enemy schema | `.../data/EnemyData.java` |
| Difficulty / plane config | `.../data/DifficultyData.java`, `ConfigData.java` |
| Shop placement / restock price assign | `.../stage/MapStage.java` (`loadObjects`, cases `"Rotating"` / `"shop"`) |
| Shop actor | `.../character/ShopActor.java` |
| Buy / restock UI | `.../scene/RewardScene.java` (`restockShop`, pricing) |
| Per-POI shop state | `.../pointofintrest/PointOfInterestChanges.java` |
| Card filters / prices / boosters | `.../util/CardUtil.java`, `AdventureReadPriceList.java` |
| Edition overlays | `.../util/AdventureOverrides.java`, `Config.java` |
| NG+ entry | `.../scene/SaveLoadScene.java` (`Modes.NewGamePlus`), `StartScene.java` |
| Player economy | `.../player/AdventurePlayer.java` |
| Shop JSON (per plane) | `forge-gui/res/adventure/{Shandalar,Innistrad,Amonkhet,Crystal_Kingdoms,Realm of Legends,Shandalar Old Border}/world/shops.json` |
| Enemies / prices / shared config | `forge-gui/res/adventure/common/world/enemies.json`, `cardprices.txt`, `common/config.json` |
| Map shop templates | `forge-gui/res/adventure/common/maps/obj/shop.tx`, `RotatingShop.tx` |
| Docs | `docs/Adventure/Towns-&-Capitals.md`, `Currency.md`, `Configure-Sets.md`, `Create-Rewards.md` |

**Key methods:**

- `MapStage.loadObjects` — picks shop identity, forces `restockPrice` (2–5 by rarity roll, **7 for Rotating**), respects `noRestock`
- `ShopActor.canRestock()` — `restockPrice > 0`
- `RewardScene.restockShop()` — spend shards → `generateNewShopSeed` → regen inventory
- `RewardData.generate(...)` — cards/gold/shards/boosters; gold = `count + random(0..addMaxCount * rewardMaxFactor)`
- `CardUtil.generateCards` / `CardPredicate` — `editions[]`, `colors[]`, `cardText`, rarity, etc.
- `SaveLoadScene` NG+ — load save, `clearChanges()`, `World.generateNew(0)`, `setCharacterFlag("newGamePlus", 1)`

### 1.2 Current behavior (findings)

**Shops are theme shops, not set shops.** Shandalar `shops.json` mostly filters by `colors` + `cardText` regex (e.g. Black1 “Certain Death”). A few entries already use `editions` (LEA Power Nine, `40K` faction shops, DnD `AFR/HBG/CLB/AFC`). Land shops use `unlimited: true`. Booster shops use `type: "cardPackShop"`.

**There is no literal “mystery” shop type.** Closest behaviors:

1. **Rotating shops** (`type=Rotating` on the map) — daily seed picks one name from a CSV `rotation` list; hourglass sign; `restockPrice = 7`.
2. **Rarity-rolled shop identity** — map properties `commonShopList` / `uncommonShopList` / `rareShopList` / `mythicShopList` randomly select which `ShopData` appears at that building.

**Restock-on-demand is first-class:** shards buy a full inventory reroll whenever `restockPrice > 0`. MapStage overwrites `ShopData.restockPrice` at placement time for Shandalar (JSON often omits it). `noRestock: true` on the map object forces `restockPrice = 0`.

**Pricing:** `CardUtil.getRewardPrice` × `AdventurePlayer.goldModifier()` × shop/town reputation modifiers. Optional `cardprices.txt` when `usePriceListPrices` is set. Sell side uses `DifficultyData.sellFactor`.

**Card pool gating today:** plane `allowedEditions` / `restrictedEditions` / `restrictedCards` / `legalCards` in `config.json`, applied in `RewardData.initializeAllCards` and booster helpers. This is a static plane filter, not progressive unlock.

**NG+ today:** keeps collection/gold; regenerates world; clears POI/quest state; sets `newGamePlus` flag (quest dialogs branch on it). **Does not expand editions or track unlock generations.**

**Enemy gold:** from `EnemyData.rewards` with `type: "gold"`. Typical easy trash: `probability 0.3`, `count 10`, `addMaxCount 90` → up to ~145 gold on Easy. `EnemyData.difficulty` already exists and is used for **spawn filtering** vs player win rank (`BiomeData.getEnemy` → `getStatistic().rank()`), but **gold ignores relative difficulty**. No diminishing returns / kill-count decay. Overworld kills remove that spawn; dungeon kills delete the object unless respawned — farmability is still high via easy overworld respawns / low-difficulty enemies that remain eligible as rank rises.

**Easy difficulty worsens the economy problem:** `rewardMaxFactor: 1.5` increases gold/card reward variance.

### 1.3 Concrete redesign (fits existing data model)

#### Design principles

1. **Geography = card access.** Each town’s shops pin to a small set of identities: one color within one set, or one whole set. Finding the town that sells MH2 green (or a full MH2 shop) is the progression, not shard-rerolling a theme shop until Mythics appear.
2. **Stock is finite until world events.** No on-demand restock. Optional: rare natural refresh only on NG+ world regen or scripted town events (not player-paid).
3. **Exploration bait.** A minority of POIs sell discounted **bundles** (fixed multi-card packs, random collections, or boosters) that you cannot buy à la carte elsewhere — reason to leave the starter biome.
4. **Gold tracks challenge.** Scale payouts by enemy difficulty vs player rank (and/or diminish repeat farms). Easy mode should not pay more for the same farm.
5. **Sets unlock across playthroughs.** Start with a curated starter pool; NG+ expands allowed editions (and thus shops/boosters that reference those sets).

#### Proposed shop model (data shape)

Keep `ShopData` / `RewardData`. Specialize via content + map wiring:

| Shop kind | How to express | Notes |
|-----------|----------------|-------|
| Set mono-color | `editions:["MH2"]`, `colors:["green"]`, `count` by rarity mix | Prefer dropping broad `cardText` regex for these |
| Whole-set | `editions:["MH2"]` only | Cap inventory size; mix rarities via multiple `RewardData` rows |
| Booster shelf | `type: "cardPackShop"`, `editions:[...]` | Already works; gate editions via unlock filter |
| Bundle / clearance | Multiple fixed `cardName` rows **or** `Union` / `sourceDeck` / small `count` random with a **price modifier** | Discount needs a small schema addition (below) |
| Land / basic | Existing `unlimited` land shops | Keep; always `noRestock` |

**Recommended schema additions (Java + JSON, small):**

| Field | Where | Purpose |
|-------|-------|---------|
| `priceModifier` (float, default 1.0) | `ShopData` | Bundle discount (e.g. 0.7) without abusing reputation |
| `shopKind` (optional string) | `ShopData` | `"set"`, `"colorSet"`, `"bundle"`, `"booster"` for UI/debug |
| `unlockedSets` / NG+ generation | `AdventurePlayer` (+ save) | Progressive edition allow-list |
| `goldScaleByDifficulty` | reward path or config | Relative gold scaling toggle |

Optional later: `restockMode: none|ngPlus|event` instead of shard restock.

#### Map / town assignment strategy

- Replace rarity-rolled empty lists and `Rotating` objects with **fixed** `shopList` / `commonShopList` entries that name the specialized shops for that town.
- Assign sets to biomes/towns deliberately (e.g. capital = flagship set, satellite towns = mono-color slices). Document the map in a plane-specific `SET_GEOGRAPHY.md` when implementing.
- Convert today’s “hourglass rotating” slots into **bundle/clearance** shops (fixed identity, changing stock only via NG+ or rare events — not daily mystery themes).
- Land shops: keep `noRestock: true` / `unlimited` as today.

### 1.4 Files to change (data vs code)

| Change | Files | Type |
|--------|-------|------|
| Specialize shop inventories | `forge-gui/res/adventure/*/world/shops.json` | **DATA** |
| Pin shop identities on maps; disable restock; remove Rotating | Town/capital `.tmx` maps under `forge-gui/res/adventure/*/maps/`, templates `shop.tx` / `RotatingShop.tx` | **DATA** |
| Lower Easy loot / enemy gold tables | `common/config.json` (`rewardMaxFactor`), `common/world/enemies.json` | **DATA** |
| Plane starter / restricted editions | `*/config.json`, `common/config.json` | **DATA** |
| Hide/remove restock UI; stop MapStage forcing restock prices | `RewardScene.java`, `MapStage.java` | **JAVA** |
| Shop-level price modifier for bundles | `ShopData.java`, `ShopActor.getPriceModifier()`, `RewardScene` | **JAVA** (small) |
| Relative gold / diminishing returns | `RewardData.generate` gold branch and/or `EnemySprite.getRewards`, `AdventurePlayer` kill tracking | **JAVA** |
| NG+ set unlock progression | `AdventurePlayer` save fields, `SaveLoadScene` NG+ branch, filter in `RewardData.initializeAllCards` / `CardUtil` booster paths | **JAVA** + light **DATA** (unlock tables) |
| UI copy / localization for shop descriptions | `languages` / shop description keys | **DATA** |

### 1.5 Phased order (smallest playable change first)

**Phase A — Kill the restock/mystery grind (playable in one weekend of content work)**  
1. Set `noRestock: true` on all card shops in target plane maps (or force `restockPrice = 0` in `MapStage` behind a config flag).  
2. Replace `Rotating` objects with fixed shop names (or disable the `"Rotating"` case via config).  
3. Optionally hide the restock button in `RewardScene` when disabled.  
**Impact:** Stops “pay shards until OP deck.” Stock becomes a finite geography problem immediately.  
**Risk:** Low. Existing saves keep bought-card tracking; shops just stop regenerating.

**Phase B — Specialize inventories (data)**  
1. Author set / color+set shops in `shops.json` (clone patterns from existing `40K` / DnD / LEA entries).  
2. Wire each town’s `commonShopList` (etc.) to those names only.  
3. Add 3–5 **bundle** shops with cheaper multi-card offers once `priceModifier` exists (or temporary: lower-count high-value packs as exploration rewards via quests if Java not ready).  
**Impact:** Exploration matters for set access.  
**Risk:** Low–medium (content balance; empty lists if names mismatch).

**Phase C — Gold anti-farm**  
1. **DATA quick win:** cut `addMaxCount` / `probability` on low-`difficulty` enemies; set Easy `rewardMaxFactor` ≤ 1.0 (recommend **0.85–1.0**, not 1.5).  
2. **JAVA:** scale gold by `enemy.difficulty / max(playerRank, ε)` (clamp), and/or diminish gold after N kills of the same enemy name this day/save. Hook points: `RewardData.generate` case `"gold"`, or wrap in `EnemySprite.getRewards()`. Prefer using existing `EnemyData.difficulty` + `getStatistic().rank()` rather than inventing a parallel level system.  
**Impact:** Easy trash stops funding mythic shopping sprees.  
**Risk:** Medium (feel of progression; need playtest on Easy/Normal).

**Phase D — NG+ set unlocking**  
1. Define unlock tiers in plane config (e.g. generation 0 = core sets, gen 1 adds modern masters, …).  
2. On NG+, increment generation / merge unlocked set codes into player save.  
3. Filter master pool + shop/booster generation through unlocked sets (intersection with plane allow-list).  
4. Ensure shops whose `editions` are fully locked show as closed, empty, or converted to “coming next cycle” — product choice.  
**Impact:** Real multi-run progression; boosters included.  
**Risk:** Medium–high (save migration, empty shops, deck legality confusion).

**Phase E — Polish**  
Quest hooks for “discover the set town,” map markers, smith/arena interaction with locked sets, adventure-editor support for new shop fields.

### 1.6 Design conclusions (author recommendations)

- Prefer **fixed geography + finite stock** over complex dynamic economies. The code already makes this mostly a content problem.
- Do **not** rely on raising card prices alone; restock + Easy gold is the actual exploit.
- Keep **one shared wallet** (gold/shards) even when designing for future co-op — see §3.
- Start changes on **one plane** (recommend Shandalar as primary; Innistrad already uses tight `allowedEditions` and is a good reference for gated pools).

---

## 2. Match AI balance (`forge-ai`)

### 2.1 Architecture map

| Area | Path |
|------|------|
| Attack decisions | `forge-ai/src/main/java/forge/ai/AiAttackController.java` |
| Block decisions | `.../AiBlockController.java` |
| Combat math helpers | `.../ComputerUtilCombat.java` |
| Controller / mana reserve | `.../AiController.java` (`reserveManaSources`, spell choice) |
| Mana payment / reserved check | `.../ComputerUtilMana.java` (`isManaSourceReserved`) |
| Counterspells | `.../ability/CounterAi.java` |
| General heuristics | `.../ComputerUtil.java`, `ComputerUtilCard.java` |
| Profiles | `forge-gui/res/ai/{Default,Cautious,Reckless,Experimental}.ai` |
| Profile keys | `forge-ai/.../AiProps.java` |
| Profile load | `AiProfileUtil`, `FPref.UI_CURRENT_AI_PROFILE`, `LobbyPlayerAi` |
| Simulation | `forge-ai/.../simulation/` (`SpellAbilityPicker`, `GameSimulator`, `GameStateEvaluator`, `SimulationController` depth 3, `OnePlaySafetyChecker`) |
| Enable sim | Lobby AI options `USE_HYBRID_SIMULATION` / `USE_FULL_SIMULATION` (desktop `PlayerPanel.getAiOptions`) — **not** `.ai` flags |
| Docs / CLI sim | `docs/AI.md` |

### 2.2 Findings relevant to Stephen’s complaints

| Complaint | What the code does today |
|-----------|--------------------------|
| Charges ahead / attacks into blockers | Aggression ladder 0–6 in `declareAttackers`; Default already has `CHANCE_TO_ATTACK_INTO_TRADE=0` and `TRY_TO_AVOID_ATTACKING_INTO_CERTAIN_BLOCK=true`. Gaps remain for “dies to any equal blocker with no upside.” Reckless is the suicide profile (`PLAY_AGGRO`, trade chance 100). |
| Rarely holds mana for counters/instants | `RESERVE_MANA_FOR_MAIN2_CHANCE=100` holds for **Main2 permanents**, not permission. Combat-trick hold exists (`TRY_TO_HOLD_COMBAT_TRICKS_UNTIL_BLOCK`). **No `RESERVE_MANA_FOR_COUNTERSPELL`.** Counters fire when the stack already has a spell (`CounterAi`). |
| No multi-turn awareness | `notNeededAsBlockers` + `ComputerUtil.predictNextCombatsRemainingLife` give **next combat** life prediction. Attrition loop is crude. Simulation depth ≤ 3 for **spell** choice only; attacks stay heuristic. `GameStateEvaluator` has `// TODO evaluate holding mana open for counterspells`. |
| Predictable | `AI_IN_DANGER_THRESHOLD` == `MAX` (both 4 on Default) → deterministic danger cutoff. Experimental sets MAX to 12 for jitter. |

**Docs (`docs/AI.md`):** AI is heuristic, best at aggro/midrange, weak at control/combo — matches the lived experience.

### 2.3 Existing knobs (use first)

High-value `AiProps` / profile lines:

| Knob | Default.ai today | Lever |
|------|-------------------|-------|
| `CHANCE_TO_ATTACK_INTO_TRADE` | `0` | Keep low for safer AI |
| `TRY_TO_AVOID_ATTACKING_INTO_CERTAIN_BLOCK` | `true` | Keep; strengthen in code |
| `CHANCE_TO_ATKTRADE_WHEN_OPP_HAS_MANA` | `30` | Lower toward `0` for less suicide into open mana |
| `AI_IN_DANGER_MAX_THRESHOLD` | `4` (same as min) | Raise to `8–12` for less cookie-cutter defense |
| `TRY_TO_HOLD_COMBAT_TRICKS_UNTIL_BLOCK` / chance | `true` / `65` | Already good for bluffs |
| `RESERVE_MANA_FOR_MAIN2_CHANCE` | `100` | Unrelated to counters — don’t expect miracles |
| `MIN_SPELL_CMC_TO_COUNTER` / `CHANCE_TO_COUNTER_CMC_*` | permissive | Cautious-style (`MIN=2`, CMC1 chance `0`) saves counters |
| `PLAY_AGGRO` | `false` | Leave false on Default |

Cautious vs Default: Cautious disables certain-block avoidance (oddly) but is stricter on countering junk CMC1s and uses higher danger max. **Do not blindly copy Cautious;** cherry-pick counter discipline + danger jitter onto Default/Experimental.

Experimental.ai’s “experimental” section is empty — simulation is **not** enabled by selecting that profile.

### 2.4 Immediate profile changes (no code)

Recommended **Default.ai** / Adventure enemy AI profile deltas:

1. `AI_IN_DANGER_MAX_THRESHOLD=10` — introduce variance in chump/trade urgency.  
2. `CHANCE_TO_ATKTRADE_WHEN_OPP_HAS_MANA=0` — stop optional trade-attacks into untapped opponents.  
3. Slightly Cautious counter discipline: `MIN_SPELL_CMC_TO_COUNTER=2`, `CHANCE_TO_COUNTER_CMC_1=15` (not 0 — still catch absurd CMC1s when `ALWAYS_COUNTER_*` applies).  
4. Keep trade-into-block avoidance on; do **not** enable `PLAY_AGGRO`.

Optional: add an `Adventure.ai` profile used by `EnemyData.ai` so Constructed Default can stay upstream-friendly while Adventure gets the tuned personality.

### 2.5 Prioritized code changes

| Pri | Change | Files | Expected effect | Risk |
|-----|--------|-------|-----------------|------|
| **P0** | Reserve mana when hand has a castable counter (new memory set + `isManaSourceReserved`; new `RESERVE_MANA_FOR_COUNTERSPELL_CHANCE`) | `AiController.java`, `ComputerUtilMana.java`, `AiProps.java`, `*.ai` | Stops tapping out with Cancel/Mana Leak in hand | Medium (underplays own Main1) |
| **P0** | Harden “no-upside attack into certain block” in `SpellAbilityFactors` / `shouldAttack` | `AiAttackController.java` | Fewer free chump-attacks | Low–medium |
| **P1** | Score open colored mana for known counter CMC in evaluator (kill the TODO) | `GameStateEvaluator.java` | Hybrid/full sim stops “spend everything” | Medium (weight tuning) |
| **P1** | Feed `predictNextCombatsRemainingLife` more aggressively into `shouldAttack` when opp has equal untapped blockers | `AiAttackController.java` | Hold back when racing math is bad | Medium (CPU) |
| **P2** | Small chance to down-tier `aiAggression` when not lethal | `declareAttackers` | Less linear attack patterns | Low if small % |
| **P2** | Broaden DeclBlk mana hold to instant removal/pump, not only tagged combat tricks | `ComputerUtilCard` path | Better open-mana play | Medium |
| **P3** | Default hybrid sim for Adventure AI seats or wire Experimental profile → hybrid | Lobby / `LobbyPlayerAi` / Adventure `DuelScene` | Safety net vs catastrophic taps | High CPU on mobile |

**What will not fix it alone:** raising Main2 reserve chance; selecting Experimental.ai; enabling full simulation without evaluator open-mana scoring.

### 2.6 Testing AI changes

- Unit tests: `forge-gui-desktop/src/test/java/forge/ai/` (`BasicAttackTests`, `BasicBlockTests`, `simulation/*`, `ReserveManaSourcesTest`).  
- Headless AI-vs-AI (from built jar): see `docs/AI.md` —  
  `java -jar forge.jar sim -d deck1 deck2 -n 50 -q`  
  Tournament modes: `-t Swiss|RoundRobin|Bracket`, `-m` best-of.  
- Manual: Constructed lobby → set AI profile → enable Hybrid/Full Simulation on AI seat → control / midrange test decks with counters.  
- Adventure: set `EnemyData.ai` to the tuned profile on a few mid/late enemies and play control mirrors.

There is **no** dedicated rebalance harness; CLI `sim` is the practical bulk tool.

---

## 3. Later: co-op Adventure as true 2HG

### 3.1 Readiness assessment

| Layer | Ready? |
|-------|--------|
| Team IDs, allies, attack-only-opponents, team win (`AllOpposingTeamsLost`) | **Yes** |
| Lobby teams / Archenemy / net Constructed | **Yes** (not Adventure) |
| Adventure 1 human vs multi-AI team (`EnemyData.nextEnemy` + `teamNumber`; e.g. Goblin Pack) | **Yes** (content rare) |
| True 2HG rules (shared 30 life, shared turn, shared combat, poison 15) | **No** — no `GameType` for 2HG; `Combat.java` TODO; poison SBA still hardcodes 10/player despite `GameRules.poisonCountersToLose` comment |
| Adventure co-op meta (2 humans, shared/party save, shops, map) | **No** |

Adventure launches via `DuelScene`: one GUI human on team 0, N AI seats from `nextEnemy`. Match end / shard sync assumes solo human. Overworld/`WorldSave`/`AdventurePlayer` are single-actor.

### 3.2 What would be needed (high level)

1. **Rules engine:** real 2HG mode (shared life/turn/combat/SBAs).  
2. **DuelScene:** second human or human+AI ally on team 0; enemy pair on team 1; hotseat or dual GUI.  
3. **Meta:** party wallet vs dual inventory; save migration; map control (one driver vs two avatars).  
4. **Netplay:** Adventure has none today; hotseat match-only is the realistic first step.

### 3.3 Keep the door open (implications for §1 and §2)

**Do:**

- Keep economy as **one party wallet** (gold/shards/life pool).  
- Keep shops/quests as single-party interactions.  
- Continue using `nextEnemy` + `teamNumber` for multi-seat fights.  
- Prefer AI changes that are teammate-aware (`GameStateEvaluator` already scores allies).  
- Prefer hotseat / duel-only co-op before networked overworld.

**Avoid:**

- Per-player gold, restock seeds, or catalogs without a party abstraction.  
- Reward code that hard-requires `humans.size() == 1` without a team path.  
- Balancing gold/AI only against 1v1 life totals if 2HG (30 life, two hands) is a real goal — parameterize where cheap.

---

## 4. Build, run, and test locally

### Requirements

- **JDK 17+** (repo `maven.compiler.release` is 17; this environment has 21)  
- **Maven 3.x**  
- Git

### Build

From repo root:

```bash
# Full desktop + adventure snapshot (Windows/Linux profile)
mvn -U -B clean -P windows-linux install

# Faster iteration on desktop only
mvn -pl forge-gui-desktop -am install -DskipTests

# Adventure / mobile-dev launcher module
mvn -pl forge-gui-mobile-dev -am install -DskipTests

# AI unit tests
mvn -pl forge-gui-desktop -am test -Dtest=forge.ai.**
```

Installer packaging also builds `forge-adventure` scripts/jars from `forge-gui-mobile-dev`.

### Run

| Mode | How |
|------|-----|
| Desktop Constructed / Quest | Run `forge.gui.GuiDesktop` (or packaged `forge.sh` / jar) with module `forge-gui-desktop` — see `docs/Development/IntelliJ-setup/IntelliJ-setup.md` |
| Adventure (desktop) | Module `forge-gui-mobile-dev`, main class `forge.app.Main` → packaged as `forge-adventure.sh` / jar-with-dependencies |
| Headless AI matches | `java -jar forge.jar sim -d deck1 deck2 -n 20 -q` (`docs/AI.md`) |

Java heap: prefer launch scripts (`-Xmx` raised); don’t rely on double-click defaults.

### Test plan for this rebalance track

1. **Shops Phase A:** enter a town → confirm no restock button / restock disabled; rotating shops gone or fixed.  
2. **Shops Phase B:** visit two towns → verify disjoint set inventories.  
3. **Gold Phase C:** farm low-`difficulty` enemies → gold/hour drops vs baseline; bosses still feel rewarding.  
4. **NG+ Phase D:** complete a run → NG+ → new sets appear in shops/boosters; old collection retained.  
5. **AI profiles:** CLI sim midrange vs control with counters; watch for fewer tap-outs and fewer suicidal attacks.  
6. **AI P0 code:** extend/add tests near `ReserveManaSourcesTest`; attack cases in `BasicAttackTests`.

---

## 5. Open questions for Stephen

1. **Which plane first?** Shandalar (huge shop list, open pool) vs Innistrad (already edition-gated) vs a fresh custom plane on the fork?  
2. **Starter set list for generation 0?** How many sets before the first NG+ unlock feels fair?  
3. **Restock:** hard-never, or allow refresh only on NG+ / rare town events (no shard button)?  
4. **Bundle shops:** random discounted singles, sealed-style packs, or curated “lot” of named cards? Target discount (e.g. 30% off)?  
5. **Gold curve:** prefer relative difficulty scaling, diminishing returns on repeat kills, or both? Softcap per day?  
6. **Locked-set shops:** invisible, boarded-up with flavor text, or visible teasers?  
7. **AI profile strategy:** retune global `Default.ai`, or add `Adventure.ai` and point enemies at it so upstream Constructed stays untouched?  
8. **Simulation cost:** acceptable to enable hybrid sim for Adventure duels on desktop only, or must mobile stay heuristic-only?  
9. **Co-op priority:** hotseat 2HG duels only, or shared overworld from day one? Shared life 30 vs “team FFA” (separate lives) as an interim?  
10. **Upstream relationship:** keep changes fork-only, or structure commits so shop data / AI profile tweaks could PR to Card-Forge later?

---

## 6. Suggested implementation backlog (after this doc)

| ID | Item | Depends | Type |
|----|------|---------|------|
| E1 | Config flag or map pass: disable restock + rotating | — | Data/Java |
| E2 | Specialize shops.json + town wiring for one biome | E1 | Data |
| E3 | Easy `rewardMaxFactor` + low-diff enemy gold nerf | — | Data |
| E4 | Relative gold scaling using `EnemyData.difficulty` | E3 | Java |
| E5 | NG+ unlock generations + pool filter | E2 | Java/Data |
| E6 | `ShopData.priceModifier` + 3 bundle shops | E2 | Java/Data |
| A1 | Default/Adventure.ai profile tweaks | — | Data |
| A2 | Counterspell mana reservation | A1 | Java |
| A3 | Stricter bad-attack filter | A1 | Java |
| A4 | GameStateEvaluator open-mana TODO | A2 | Java |
| C1 | Spike a 2HG rules spike separately from Adventure | — | Java |
| C2 | Adventure party save sketch (doc only until E/A stable) | C1 | Design |

**Do not start C1/C2 until E1–E4 and A1–A3 feel good in play.**

---

## Appendix: quick reference — restock assignment today

From `MapStage.loadObjects`:

| Source | `restockPrice` |
|--------|----------------|
| Rotating shop | 7 |
| Mythic list roll | 5 |
| Rare | 4 |
| Uncommon | 3 |
| Common | 2 |
| Legacy `shopList` | 0 |
| `noRestock: true` | forced 0 |

Shard restock lives in `RewardScene.restockShop()`; shards are also sold via Shard Trader (`docs/Adventure/Currency.md`).
