# Bellwarden: Planes of Nothing — Crafting, Gathering and Gear Brief

This brief is for agents working on the `sjones321/forge_adventure` fork, branch `feature/set-start`.
It covers three phases: wildcard crafting, overworld gathering, and craftable gear.
Build them in order. Phase 1 must be merged before Phase 2 or 3 starts.

> **Status:** Phase 1 is merged. Phases 2 and 3 below are superseded by `Ascendant-Roadmap.md`;
> the ground rules and code pointers in this file still apply.

## Ground rules (all phases)

- **Everything is gated to Bellwarden: Planes of Nothing.** Check `Config.ascendant()` (backed by `ascendantRules` in
  `forge-gui/res/adventure/Shandalar Ascendant/config.json` — internal folder id unchanged). Stock worlds must behave exactly as before: no new buttons,
  no new currencies shown, no new world objects.
- **Tunable numbers go in `ConfigData.java`** with defaults, and are set in the mode `config.json`.
  Do not hard-code balance numbers in scene code.
- **Save compatibility is mandatory.** Old saves must load. Every new saved field must be optional on load
  (missing → default). Saves use `SaveFileData` (`store`/`readInt`/`readObject`); see `AdventurePlayer.save()`/`load()`
  around lines 600-1020.
- **Skills** live in `forge-gui-mobile/src/forge/adventure/player/PlayerSkills.java`. XP curve is RuneScape's (1-99).
  `addXp` already returns 0 when Planes of Nothing rules (`Config.ascendant()`) are off. New skills are added to the `Skill` enum and must appear on
  `SkillsScene` and `UnlocksScene` automatically (check that they do).
- **Only change `forge-gui-mobile` and `forge-gui/res/adventure`** unless a phase says otherwise. No new dependencies.
- **Checkstyle fails the build on unused imports.** Build before opening a PR:
  `mvn -B -q -o install -DskipTests -pl forge-gui-mobile,forge-gui-mobile-dev -am` (drop `-o` if offline cache is missing).
- **JSON files**: write UTF-8 without BOM.
- One PR per work package below. PR description lists what to playtest.

## Existing code you will touch

| Area | Where |
|---|---|
| Player state, save/load, selling | `forge-gui-mobile/src/forge/adventure/player/AdventurePlayer.java` (`cardSellPrice` ~1472, `sellCard` ~1491, `performSale` ~1555, `addShards`/`takeShards` ~1318, `win()` ~1297, inventory ~432-1625, save ~1000-1020, load ~620-690) |
| Standard window and legality | `player/StandardWindow.java` (`getSets`, `addSet`, `expandedCodes`, `isStandardLegal`, `saveSets`/`load`) and `AdventurePlayer.isStandardLegal(PaperCard)` |
| Vault | `AdventurePlayer.vaultCards`, `copiesAvailableToVault`, `vaultedCount` |
| Deck editor sell menu | `scene/AdventureDeckEditor.java` (sell menu items ~333/340, ~711/718, ~740) |
| Spell Smith | `scene/SpellSmithScene.java` |
| Items | `data/ItemData.java`, `data/EffectData.java`, `data/ItemListData.java`, `forge-gui/res/adventure/common/world/items.json` |
| Item effects in duels | `scene/DuelScene.java` `addEffects` (~358) and the `playerEffects` loop over `getEquippedItems()` (~447) |
| Overworld spawning | `stage/WorldStage.java` `handleMonsterSpawn` (~285), `spawn(EnemySprite)` (~332), collision (~120), `save()`/`load()` (~446-481) |
| Pickup actor | `character/RewardSprite.java` (constructor `(String data, String sprite)` needs no map id) |
| Biomes | `data/BiomeData.java`, `forge-gui/res/adventure/<world>/world/*.json` |

---

## Phase 1 — Wildcard crafting (Arena-style, grindable)

### Rules
- Four separate currencies, one per rarity: **Common, Uncommon, Rare, Mythic dust**. They never convert into each other.
  Special/bonus rarities count as Rare; basic lands cannot be salvaged or crafted.
- **Salvage**: destroying one copy of a card gives dust of that card's rarity.
  - Base yield `salvageDust = 10` per copy. The Salvaging skill raises it linearly to `salvageDustMax = 20` at level 99.
  - Same protections as selling: vaulted copies and copies used in decks cannot be salvaged; `hasNoSellValue()` cards
    cannot be salvaged.
  - Salvaging grants Salvaging XP (reuse the scale in `PlayerSkills` line ~250).
- **Craft**: spend dust of the card's rarity to add one copy to the collection.
  - Base cost `craftCost = 50` (≈5 salvages per craft at level 1, ≈2.5 at 99).
  - **Craftable pool**: any card with a printing in a set the player has **ever unlocked** (current window, rotated-out
    window sets, and the expanded Core Set Collection), plus currently unlocked staples.
  - Cost is **×1 if the card is Standard-legal right now**, **×2 otherwise** (`historicCraftFactor = 2`).
  - Rarity used for cost: the rarity of the printing being crafted. Craft the newest printing among unlocked sets.
  - Spellsmithing skill gives a discount: up to `craftDiscountMax = 0.25` at level 99. Crafting grants Spellsmithing XP.
  - Respect ban lists (`BanLists.java`): a card banned in all three formats cannot be crafted.
- **Auto-salvage option**: a toggle that salvages every copy beyond the 4th when cards are gained (not for basics,
  not for vaulted copies). Off by default.
- **Dust income besides salvage**: small dust drops from duel wins (e.g. 2 common dust per win, bosses give rare dust),
  configured in `ConfigData`. Spell Smith random pulls stay as they are.

### Data
- `AdventurePlayer`: `int[] dust` (C/U/R/M), getters, `addDust(rarity, n)`, `takeDust`, change signal like shards.
  Save key `dust`; missing → zeros.
- `StandardWindow`: new `List<String> unlockedHistory` — every set ever added to the window. Saved; on load of an old
  save, seed it from the current window sets. `isEverUnlocked(code)`.
- Methods: `salvageCard(PaperCard, int amount)` (mirrors `sellCard`), `canCraft(PaperCard)`, `craftCost(PaperCard)`,
  `craftCard(PaperCard)`.

### UI
- Deck editor: "Salvage" menu item next to every "Sell" item, showing the yield. Planes of Nothing only.
- Dust totals shown where gold/shards are shown in the deck editor and the inventory/status screen.
- **Crafting screen** (new scene, reached from the deck editor and from Spell Smith in Planes of Nothing):
  search box by card name, results list with hover preview (existing `HoverPreview`), cost and owned count per result,
  a "Craft" button. Card-name search uses `StaticData` card DB filtered to the craftable pool.

### Acceptance
- Old Planes of Nothing save loads, shows 0 dust, unlocked history equals the current window.
- Stock Shandalar save shows no dust, no Salvage, no crafting screen.
- Salvaging a vaulted card or a card in a deck is refused with a message.
- A rotated-out card costs 2× and is still craftable; a card from a never-unlocked set does not appear in search.

---

## Phase 2 — Gathering on the overworld

### Rules
- **Resource nodes** spawn on the overworld near the player, like enemies, but they do not move and last longer.
  Walking into a node gathers it (no minigame). A node the player is too low level for shows a message and stays.
- **Resources by biome** (biome names from the world's biome JSON files; map each biome to one resource family):

  | Family | Biome | Skill | Tiers (level needed) |
  |---|---|---|---|
  | Lumber | Forest (green) | Woodcutting | Oak 1, Willow 15, Yew 40, Ironwood 70 |
  | Ore | Mountain (red) | Mining | Copper 1, Iron 15, Mithril 40, Adamant 70 |
  | Stone | Plains (white) | Masonry | Rough 1, Granite 15, Marble 40, Starstone 70 |
  | Herbs / Bone | Swamp (black) | Foraging | Nightshade 1, Grave moss 15, Bone 40, Wraithbloom 70 |
  | Crystal | Island (blue) | Delving | Quartz 1, Azure 15, Prismatic 40, Aether 70 |

  Tier spawn odds weight toward the player's level; higher levels gather 1-3 units instead of 1.
- New skills added to `PlayerSkills.Skill`: Woodcutting, Mining, Masonry, Foraging, Delving. Each gather grants XP by tier.
- Rare drops from nodes: occasional dust (common/uncommon) and very rarely a gold/shard bonus.

### Data and code
- **Materials inventory** (shared with Phase 3 — define this first and keep it stable):
  `AdventurePlayer.materials : Map<String,Integer>`, `addMaterial(id, n)`, `takeMaterial(id, n)`, `getMaterial(id)`,
  change signal; saved as two parallel arrays (`materialIds`, `materialCounts`); missing → empty.
- Material definitions in a new `forge-gui/res/adventure/common/world/materials.json`:
  `{ id, name, family, tier, skill, levelRequired, xp, iconName, biome }`.
- `stage/WorldStage.java`: a `nodes` list parallel to `enemies`, spawned on a timer in the current biome, max ~4 alive,
  despawn on a lifetime, collision via `player.collideWith`, saved/loaded with the world like enemies. Node actor can
  extend `RewardSprite` or `MapActor`; reuse existing sprites (biome decoration sprites, `sprites/treasure.atlas`) —
  do not commission new art. A distinct tint per family is enough.
- Materials appear on a "Materials" tab of the inventory scene with counts.

### Acceptance
- Stock worlds spawn no nodes.
- Nodes never spawn on colliding tiles; gathering is not possible during a duel transition.
- Saving next to a node and reloading keeps the node.

---

## Phase 3 — Gear crafting

### Rules
- A **Forge** building in towns (Planes of Nothing only), reusing the Spell Smith map object pattern in `MapStage`
  (or a button inside Spell Smith if adding town objects is too invasive).
- Recipes in `forge-gui/res/adventure/common/world/recipes.json`:
  `{ result (item name), materials: {id: count}, gold, skill, levelRequired, xp }`.
- New crafting skill **Smithing** (gear) — Spellsmithing stays for cards.
- **Gear lines per slot**, four tiers matching the material tiers (e.g. Copper → Iron → Mithril → Adamant blades
  for Right hand; Oak → Willow → Yew → Ironwood shields for Left; Body, Boots, Neck likewise from stone/herb/crystal).
  New items go in the mode world's own `world/items.json`, using only existing `EffectData` fields
  (`lifeModifier`, `changeStartCards`, `startBattleWithCard`, `moveSpeed`, `goldModifier`, `cardRewardBonus`,
  `freeMulligans`, `opponent`). Effects scale by tier; top tier also needs a boss material.
- Existing ~200 items stay as loot and shop stock unchanged.
- Optional: salvaging gear back into some of its materials.

### Acceptance
- Crafted items equip, save, reload, and apply their effects in duels via the existing `addEffects` path.
- A recipe the player lacks the level or materials for is visible but disabled, showing what is missing.

---

## Work packages (for parallel agents)

1. **P1 core** — dust data, salvage, craft rules, unlocked history, save/load. (Phase 1, no UI.)
2. **P1 UI** — Salvage menu items, dust display, crafting screen. Depends on 1.
3. **Materials API** — the materials inventory and `materials.json`. Tiny; merge before 4 and 5.
4. **Gathering** — skills, node spawning, collision, save/load, Materials tab. Depends on 3.
5. **Forge and recipes** — skill, recipe data, Forge UI, gear items. Depends on 3.

Packages 4 and 5 can run at the same time. Packages 1 and 2 touch `AdventurePlayer` heavily — do not run them in
parallel with anything else that edits that file.
