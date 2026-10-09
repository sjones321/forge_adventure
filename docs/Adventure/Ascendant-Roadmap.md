# Shandalar Ascendant — Roadmap: Gathering, Crafting, World and Gyms

This roadmap replaces Phases 2 and 3 of `Ascendant-Crafting-Brief.md`. Phase 1 (wildcard dust) is merged.
The **ground rules** in that brief still apply to every package here: Ascendant-only (`Config.ascendant()`),
tunables in `ConfigData` + the Ascendant `config.json`, old saves must load, one PR per work package, build before PR,
no unused imports, JSON without BOM.

Goal: the definitive Forge Adventure. The loop is **gather → refine → craft → equip → fight harder → rarer materials**,
with a second progression track of **gym badges and a League**.

## Design decisions (settled)

- Gathering is a **1-3 second channel** while standing still. Walking away or an enemy touching the player cancels it.
  Higher skill level shortens it.
- **Tools gate tiers** (axe, pickaxe, etc.). Tools live on a toolbelt, not in equipment slots.
- **Gear never breaks.** No durability or repairs. Bulk sinks (homestead, town requests) do that job.
- **Full skill set**: 5 new gathering skills + 4 new crafting skills, 20 skills total.
- **Any gathered material can be refined into card dust**, so gathering is a full alternative path to crafting cards.
- **One continuous world, no New Game+ reset.** You progress by mastering sets and opening portals to new planes;
  your prime base stays reachable. The only resets are Standard rotation (cards become Historic, nothing is lost)
  and **prestige**, which is what New Game+ means from now on (2026-10-08).
- **Formats: Bellwarden Standard, Historic, Pauper and Commander**, each with its 2HG version for co-op. Real-world
  Standard is not offered. The format is set **per plane** (package K) and decides what that plane's enemies, gyms
  and events play.
- **Nothing you earn is ever locked away by format.** Crafted and earned cards stay in the collection; rotation only
  changes which formats they are legal in.
- Map rework and new world locations only affect **new games** (the world is generated at New Game). Gyms and
  everything else must work on existing saves.

## Skills and materials

**One material set serves both card crafting and gear crafting.** Every color has a gathered material line that
is used as that color's **mana reagent** when crafting cards (package A2) and as a gear ingredient (E, F).

| Kind | Skill | Source / output |
|---|---|---|
| Gathering | Quarrying | Plains nodes → sacred stone (white) |
| Gathering | Delving | Island nodes → waters and ice (blue); crystal and pearls on rare rolls |
| Gathering | Foraging | Swamp nodes → bones and dead things (black); forest plant nodes → plants (green) |
| Gathering | Mining | Mountain nodes → ash and fire minerals (red) from vents; ore (colorless) from veins; gems on rare rolls |
| Gathering | Woodcutting | Forest trees → logs (gear, and burned into charcoal) |
| Gathering | Salvaging (existing) | Wastes nodes → scrap (colorless, alternative to ore) |
| Crafting | Smithing | Ore → bars → weapons, armor |
| Crafting | Woodworking | Logs → bows, shields, staves, charcoal |
| Crafting | Alchemy | Plants, bones, waters → potions (one-duel boosts) |
| Crafting | Jewelcrafting | Gems, crystal, pearls → rings, amulets |
| Crafting | Spellsmithing (existing) | Cards (dust + reagents), and refining surplus materials into dust |

Each line has four tiers, needing levels 1 / 15 / 40 / 70 to gather. **Card rarity picks the reagent tier**:
common → T1, uncommon → T2, rare → T3, mythic → T4.

| Line (color) | T1 | T2 | T3 | T4 |
|---|---|---|---|---|
| Sacred stone (W) | Limestone | Marble | Sunstone | Starstone |
| Waters (U) | Spring water | Glacier ice | Purified water | Elemental water |
| Dead things (B) | Bone fragments | Grave moss | Ancient bone | Wraithbloom |
| Ash (R) | Ash | Charcoal | Brimstone | Dragonfire ash |
| Plants (G) | Wild herbs | Tree sap | Elder seeds | Worldtree bark |
| Ore (C) | Copper | Iron | Mithril | Adamant |
| Logs (gear) | Oak | Willow | Yew | Ironwood |
| Scrap (C alt.) | Scrap | Brass gears | Thran parts | Thran relic |

Enemy drops add alternates by color: **feathers** (white), **hide** (green/white), **bone** (black), **ash** (red),
**brine** (blue). Feathers and hide count as their color's reagent of the same tier.
**Prismatic** reagents (T1-T4) are crafted from one reagent of each color; see A2.

Gems (Mining, any tier, rare roll): Garnet, Sapphire, Emerald, Ruby, Onyx. Boss materials: one unique per boss.

## Standing rule: every package says what it means in co-op (decided 2026-10-09)
Co-op with a friend is part of the definitive game, not an add-on. Every package spec must include a **Co-op** bullet:
what both players see and do, who is authoritative (the host), what is shared and what is per player, and how it is
tested with two players. "Not applicable" is allowed only with a reason. Reviews reject packages that ship solo-only by
accident.

## Work packages

Dependencies are listed; packages without a dependency between them can run in parallel.
Package A must merge first.

### A. Materials core
- `AdventurePlayer.materials : Map<String,Integer>` with `addMaterial`, `takeMaterial`, `getMaterial`, change signal.
  Save as parallel arrays `materialIds` / `materialCounts`; missing → empty.
- `forge-gui/res/adventure/common/world/materials.json`: `{ id, name, family, tier, skill, levelRequired, xp,
  iconName, biome, sellPrice, dustRefine: { rarity, amount } }`.
- New reward type `"material"` in `data/RewardData.java` (next to `"item"`, `"gold"`, `"shards"`, ~lines 233-375)
  and handled in `AdventurePlayer.addReward`.
- **Enemy drops by color**: each enemy rolls a small material drop on a win from a table keyed by its colors
  (white: stone/hide, blue: crystal, black: bone/herbs, red: ore, green: logs/hide, colorless: scrap).
  Bosses always drop their unique material. Table in a JSON file, not code.
- **Materials tab** in the inventory scene with counts and sell buttons; shops buy materials at `sellPrice`.
- **Refine to dust** (Spellsmithing): at Spell Smith, a Refine screen turns materials into dust. Default mapping
  T1 → common, T2 → uncommon, T3 → rare, T4 and boss materials → mythic. Amounts are tunable and should make one
  hour of gathering worth roughly one hour of dueling in dust. Spellsmithing level raises the yield.

### A2. Reagent card crafting (depends on A; data from B)
Crafting a card costs **dust by rarity (Phase 1, unchanged, ×2 when not Standard-legal)** plus **mana reagents**:
- One reagent per **colored mana symbol** in the card's cost, of that color's line, at the tier set by rarity.
  Example: a common {1}{G}{G} costs common dust + 2 Wild herbs; a mythic {5}{G}{G}{G} costs mythic dust +
  3 Worldtree bark.
- **Multicolor** cards pay each symbol in its own color ({W}{U} = 1 white + 1 blue reagent).
- **Hybrid** symbols accept either color; **Phyrexian** symbols need their color.
- **Colorless cards** (artifacts, Eldrazi, {C}) pay **ore equal to mana value, capped at 4**, minimum 1. Scrap of the
  same tier can replace ore.
- **Lands** and cards without a mana cost pay one reagent per color in their color identity, or 1 ore if colorless.
- **"Any color" producers** (lands or other cards with a mana ability that adds mana of any color, e.g. City of
  Brass, Mana Confluence, Command Tower, Exotic Orchard; detect via the card's mana abilities producing `Any`)
  pay **1 Prismatic reagent** of the card's tier instead of the identity rule.
- **Prismatic reagents** are made at Spell Smith from **one reagent of each of the five colors** of the same tier
  (5 → 1), so they need all five gathering lines. Rare Delving crystal rolls can also drop one.
- The craft screen shows the reagent cost next to the dust cost, and what is missing in red.
- Refining surplus materials into dust (package A) stays as an alternative.

### B. Gathering on the overworld (depends on A)
- **Material lines changed after A merged**: update `common/world/materials.json` to the color lines in
  "Skills and materials" (sacred stone, waters, dead things, ash, plants, ore, logs, scrap, plus feathers and
  brine as drops) and update `enemy_material_drops.json` to match. Keep ids stable where a line kept its item
  (ore, logs, scrap, stone tiers can be renamed in `name` only). Saves holding retired ids must still load;
  unknown ids are ignored by the UI.
- Add the 5 gathering skills to `PlayerSkills.Skill`.
- **Tools**: toolbelt in `AdventurePlayer` (one tool per family, saved). Tool tier caps the node tier you can gather.
  Tools are crafted (package E) or bought from general stores; T1 tools are given free at New Game and on old saves.
- **Nodes**: `WorldStage` keeps a `nodes` list parallel to `enemies` (`handleMonsterSpawn` ~285, `spawn` ~332).
  Spawn in the current biome on a timer, max ~4 alive, longer lifetime than enemies, never on colliding tiles.
  Tier odds weight toward the player's level. Saved/loaded with the world like enemies (~446-481).
- **Channel**: touching a node starts a 1-3 s channel with a progress bar; moving or an enemy collision cancels it.
  Yield 1-3 units by level; rare roll for gems or a little dust.
- Reuse existing sprites (biome decoration, `sprites/treasure.atlas`) with a tint per family. No new art required.

### B2. Upgradeable gathering methods (depends on B; deepens with D, E)
Gathering gets better through four layers:
1. **Tool tiers** (B): each tool tier gathers one more node tier.
2. **Method upgrades per skill**, crafted with materials, each changing how gathering works, not just numbers.
   Examples: Woodcutting hatchet → felling axe (fells adjacent trees too) → lumber crew; Mining pick → drill
   (shorter channel) → blasting charge (whole vein at once); Delving bucket → still (purifies water to the next
   tier) → elemental condenser; Foraging sickle → herb pouch (double plant yield) → grave lantern (finds rare
   dead things); Quarrying chisel → stonecutter's saw → consecrated quarry tools.
3. **Tool enchantments**: sockets on tools filled with gems or crystal for perks (faster channel, double-yield
   chance, rare-find chance, auto-refine to dust).
4. **Outposts**: claim a resource location (D) and build a camp (mine shaft, logging camp, well, bone pit,
   herb garden, shrine quarry) with materials. Camps produce materials over in-game time into storage capped at
   a few days' output; collect on visit. Camp levels raise output and tier. Nothing is lost for not visiting
   except output while full.
Upgrades and camps are data (`world/gathering_methods.json`).

### C. World generation rework (no dependency; new games only)
- Give Shandalar Ascendant its own copies of `world/biomes/*.json` and `world/points_of_interest.json` so stock worlds
  are untouched.
- **Bigger map**: Ascendant `world/world.json` width/height from 700 to ~1000 (test generation time and memory;
  the minimap is `miniMapTileSize` px per tile).
- **Fewer, spread-out towns**: today the biomes place ~424 towns on a 700×700 map, and the placement in
  `world/World.java` (~465-545) only keeps an 8×8-tile rectangle free around each POI, so towns end up adjacent.
  Add a `minTownSpacing` (tiles) to `world.json`, applied to `town` and `capital` POIs in that placement loop.
  Target ~120-150 towns, at least ~40 tiles apart.
- **Travel for a bigger map**: Exploration skill unlocks waypoint travel between visited towns (town board,
  gold cost by distance, discount with level). Roads give a movement speed bonus if they don't already.

### D. Resource locations (depends on B and C)
- New POI types per biome, 6-10 each: **Mine** (mountain), **Old-growth Forest** (forest), **Quarry** (plains),
  **Herb Bog** (swamp), **Crystal Grotto** (island), **Scrapyard** (wastes).
- Interiors are tiled maps (start from existing cave/forest maps) with a new `node` map object type handled in
  `stage/MapStage.java`'s object switch (~407-681). Deeper rooms have higher-tier nodes and guardian enemies;
  nodes inside respawn on a timer per location.

### E. Recipe engine and stations (depends on A)
- Add Smithing, Woodworking, Alchemy, Jewelcrafting to `PlayerSkills.Skill`.
- `world/recipes.json`: `{ id, station, result (item id or tool or potion), materials {id: count}, gold, skill,
  levelRequired, xp }`.
- **One crafting screen** for every station: recipe list filtered by station, requirements shown, missing items in
  red, recipes above your level visible but disabled.
- **Stations** in towns: Forge (Smithing), Workshop (Woodworking), Apothecary (Alchemy), Jeweler (Jewelcrafting).
  Add them as map objects in existing town maps (the way `spellsmith` objects are handled in `MapStage`) so they work
  on existing saves. Not every town has every station; capitals have all.

### F. Craftable content (depends on E)
- **Gear lines**: four tiers per slot from the material families. Effects use `EffectData` fields, and from T2 up
  each piece also starts the duel with a **custom command-zone card** (`startBattleWithCardInCommandZone`) scripted in
  `forge-gui/res/adventure/common/custom_cards/` (68 examples exist there, e.g. `garruks_mighty_axe.txt`). Example:
  Mithril Blade — "{2}, {T}: Target creature you control gets +1/+0 until end of turn." T4 pieces need a boss material.
- **Potions** (Alchemy) use the existing next-battle **blessing** (`AdventurePlayer.addBlessing`), so no new duel code.
- **Jewelry** (Jewelcrafting): rings and amulets for the Neck slot and a new second ring slot if cheap to add.
- **Tools** for package B.
- Existing ~200 items stay as loot and shop stock.

### G. Gyms and the League (no hard dependency; must work on existing saves)
- **Gyms play their plane's format** (package K). A gym on a Pauper plane is Pauper, and so on. Your deck must be
  legal for that format to challenge the gym.
- **8 gyms, each themed by color**: White, Blue, Black, Red, Green, Colorless (artifacts/Eldrazi),
  Guild (two-color), and Rainbow (three or more colors). Theme decides the leader's and trainers' decks, the town
  the gym sits in, and the badge perk.
- Each gym has 2-3 trainer duels before the leader. Gyms can be done **in any order**; leader decks scale with the
  number of badges held. Leader matches are best of 3.
- **Badges** give a perk each, unlock higher-tier crafting recipes, and are shown on the Skills/Unlocks screens.
  Rewards: gold, dust, a unique material, and a theme staple.
- **League**: with 8 badges, the Elite Four and the Champion at a central castle, best of 3 each, no healing between
  matches. Beating it unlocks rematches: leaders and the League return with harder decks and a repeatable reward table.
- Gym buildings are added inside existing town maps, so they work on current saves.
- Gym decks per format live in data (`world/gyms.json` + deck files), so new formats only need new deck lists.

### H. Town requests (depends on A)
- Town boards post material delivery requests through the existing quest system: deliver N of a material for gold,
  skill XP and town reputation.

### I. Homestead (superseded by the Fortress packages FT1-FT4)
- A player-owned plot with buildings made from bulk logs and stone: stations, a storage chest, the vault, a trophy
  room for badges. RuneScape's Construction, as the main long-term material sink.

### J. Skill trees (depends on nothing; replaces flat perks)
Today every skill is linear: small per-level bonuses (prices, speed), color perks at 15/40/75, and staples at fixed
levels (`player/PlayerSkills.java`, `COLOR_PERKS` ~268, staple files `common/staples_<color>.txt`). Replace the
perk layer with a tree per skill so more levels matter and builds differ:
- **Talent points**: 2 points at every level divisible by 10, 1 point at the other multiples of 5
  (5, 15, 25 ... 95). That is 28 points by level 99, spent only in that skill's tree.
  Staple unlocks stay on their fixed levels; per-level passive bonuses stay.
- **Each tree has 3 branches** of ~8 nodes, some with multiple ranks, so 28 points fill about two branches and
  never the whole tree. Some tiers are a choice of one of two nodes, so two players with the same
  levels can play differently. Milestone capstones at levels 50 and 99; 99 also grants a **skill cape**
  (RuneScape-style cosmetic plus a capstone perk).
- **Perk slots**: duel-affecting perks (life, tokens, cards, opponent effects) must be slotted to be active.
  Slots grow with total level (e.g. 3 at start, up to 10). Non-duel perks (prices, gathering speed, travel) are
  always on. This keeps 20 skills of perks from stacking into an unwinnable-for-AI pile.
- Perk effects use `EffectData` fields or custom command-zone cards (`common/custom_cards/`), so any Magic effect
  is possible.
- **Respec** for gold, cost rising each time; free respec at New Game+.
- Trees are data (`world/skill_trees.json`: node id, branch, tier, cost, requires, exclusiveWith, effect), shown on a
  tree screen reached from the Skills screen. Existing saves: refund color perks into points automatically.

### K. Formats per plane (revised 2026-10-08; replaces "run formats and New Game+")
- Formats: **Bellwarden Standard** (the in-game rotation window), **Historic**, **Pauper**, **Commander**. Co-op
  duels use each format's 2HG version.
- **The home plane's format** is chosen on the New Game screen (default Bellwarden Standard) and again at each
  prestige (M).
- **Each new set plane picks its format when it is opened** (the portal dialog in MV2; default = the format of the
  plane you came from). Once chosen it is fixed for that plane. This keeps commitment early while letting the
  player shift formats as they move through sets.
- A plane's format sets: its overworld enemy decks (EN1), its gyms and League, its tournaments, and which card pool
  its shops and rewards favor. Gyms, the League and tournaments need a deck legal in that format; ordinary
  overworld fights accept any deck (tunable, so a later "strict" option can require legal decks everywhere).
- Store the format on the plane (in its plane data), not on the player. Migrate the existing player-level
  `runFormat` (`AdventurePlayer`) to the home plane's format; `getRunFormat()` becomes "the current plane's format".
- Co-op: the host's plane format applies to both players.
- **Pauper**: cards printed at common in any set (use Forge's Pauper format definition under
  `forge-gui/res/formats/Sanctioned/`). Add `AdventureHistoric`-style deck tag `AdventurePauperDeck` and a
  Pauper option in the deck format cycle.
- The current plane's format is shown on the status screen and in the portal dialog.

### EC1. Game-based card economy (Ascendant; replaces real-world prices)
Card prices come from the game, not from real-world market data, so no card is a jackpot (an early Badlands is
worth what its rarity and power say, not hundreds of dollars).
- **Base value by rarity** (tunable table in the Ascendant config): common, uncommon, rare, mythic, special. Basic
  lands are worth nothing.
- **Power within its set** from Forge's own draft pick rankings (`forge-gui/res/draft/rankings`): a card ranked near
  the top of its set is worth more than a weak card of the same rarity, so a strong common can outprice a bad rare.
  Cards with no ranking use the rarity base. The multiplier range is tunable (e.g. 0.5x to 3x).
- **Rotation**: cards legal in the current Standard window are worth more (demand); rotated (Historic-only) cards
  less. With K, use the format of the plane the shop is on.
- **Living local markets** (per world save): each town shop tracks supply per card. Selling copies there lowers its
  price, buying raises it, and prices drift back toward the base over in-game days. Towns lean by color (a red
  plane's town pays more for red cards). Bounded so prices never leave a tunable band around the base.
- **Selling pays a fixed share** of the current local price (tunable, about 30%). Auto-sell and bulk sell use the same.
- **One source of value**: dust refine and craft costs (A2), salvage yields and shop restock prices read the same
  value function, so buying, crafting and salvaging stay consistent.
- Replace `CardUtil.getCardPrice` usage in Ascendant with the new function; the stock world keeps real prices.
- Tests: a ZEN common vs a ZEN rare by ranking; Badlands priced by rarity/power (no market data); selling five copies
  in one town lowers that town's price and not another's; drift back over time; sale share applied; stock world
  unchanged.

### L. Tournaments and lifetime stats (depends on K)
- **Tournament ring**: inns run small events (the existing inn event code: draft, jumpstart, sealed) plus
  constructed events in the run format.
- **Grand Prix** in capitals: scheduled every N in-game days, entry fee, Swiss rounds then a top 8, prizes scale with
  placement (gold, dust, packs, unique materials, trophies).
- **Lifetime stats** that survive prestige: tournaments entered, top 8s, GP wins, badges and League
  titles per run format, total duels, best win streak. Shown on a **Hall of Fame** screen.

### M. Prestige (depends on J and L)
- **Prestige is the game's New Game+**: a full account reset: collection, gold, dust, materials, items, skills and
  decks are wiped. Kept: unlocked staples, lifetime stats / Hall of Fame, prestige level, prestige tree, cosmetics
  (skill capes, trophies), achievements (AC1) and every unlocked card style (CS1). At prestige the player picks
  the new home plane's format (K).
- **Requirements are steep**, e.g. League champion in the current run, total level 1000+, and a Grand Prix win.
  Exact numbers are tunables.
- Each prestige grants **prestige points** for a **prestige skill tree** (account-wide perks: faster XP, extra perk
  slot, better starting sealed pool, etc.) and lets the player choose cards from a **prestige staples list**:
  powerful cards (format staples above normal staple power) that become permanently Standard-legal staples for that
  account. Prestige staples still respect ban lists for Commander/Historic.
- Prestige needs a confirmation screen that lists exactly what will be lost.

## RPG systems

### N. Rifts — roguelite dungeon runs (no hard dependency)
- A Rift entrance POI per region. The player enters with **no deck from their collection**: they draft or pick cards
  floor by floor (reuse the inn event draft/Jumpstart code), fight a few encounters per floor, and pick **relics**
  (run-only effects as `EffectData` or custom command-zone cards) between floors. A boss ends each Rift.
- Losing ends the run. Rewards earned on cleared floors are kept: gold, dust, materials, and a card or two from the
  run deck. Rift depth records go to the Hall of Fame.
- Rift tiers unlock by clearing the previous tier; higher tiers scale enemies and rewards.

### O. World bosses and invasions (no hard dependency)
- **Invasions**: periodically a region is invaded (e.g. Phyrexian incursion). Themed enemies spawn there until the
  invasion's world boss is beaten; rewards include unique materials.
- **World bosses** fight in Forge's **Archenemy** format (`GameType.Archenemy`, scheme deck) with boosted life.
- Invasions run on in-game days, and missing one costs nothing; another comes.

### P. Companions (depends on nothing; deepens with J)
- Recruitable NPCs (quests, gyms, factions). A companion joins boss fights as an AI ally on the player's team.
  Adventure duels already support multiple players with team numbers (`DuelScene` `setTeamNumber`); use team
  multiplayer, not shared-life 2HG, unless the engine's 2HG is easy to drive.
- Companions level with use; the player edits their deck from a companion-only card pool and picks their gear.
- One companion active at a time; companions are skipped in normal overworld fights unless the player opts in.

### Q. Familiars (depends on J for perk slots)
- A pet that starts every duel as a token (custom card script). It gains XP from duels and **evolves** at set
  levels (e.g. 1/1 → 2/2 with an ability → 4/4 with a stronger ability). Several familiar lines, one per color.
- Uses a duel perk slot, so it competes with skill perks.

### R. Ironman and Hardcore Ironman (needs K's New Game screen)
- Chosen at New Game or at prestige. **Ironman**: shops never sell cards or packs, Spell Smith random pulls are allowed,
  crafting is allowed; everything else is self-found. **Hardcore**: losing your last life ends the character
  (save becomes read-only, stats go to the Hall of Fame).
- Mode tag shown on the status screen and Hall of Fame entries.

### S. Crafted gear quality (depends on F)
- Each crafted item rolls **Normal / Fine / Masterwork**; odds improve with the crafting skill level above the
  recipe's requirement. Better quality boosts the item's numbers (e.g. +1 life, an extra token) — no random affix
  soup. Quality is part of the saved `ItemData`.

### T. Card mastery (no dependency)
- Cards gain XP when they are in a deck that wins. Mastery levels unlock that card's **foil** and then an
  **alt-art** printing in the player's collection (cosmetic). Shown in the deck editor and on the Unlocks screen.

### U. Bestiary (no dependency)
- Records every enemy type beaten (count, first win date). After N wins against an enemy, the Bestiary shows its full
  decklist. Bestiary completion feeds the Collecting skill and the Hall of Fame.

### V. Planeswalking (depends on C)
- Each plane is built around **one set**; reaching a plane adds its set to the unlock pool, matching "new sets come
  from new planes". Endgame travel between planes through planar portals (existing `PortalActor`).
- Builds on the earlier plane-per-set generator idea: a plane is generated from a template world plus the set's
  themes (enemy decks from that set, town names, biome mix).

### W. Factions and reputation (depends on H's quest work)
- One faction per color (e.g. Order of the Plains, Tide Council, Night Court, Forge Clans, Wildwood), plus a
  colorless guild. Reputation from quests, contracts and duels against rival factions' enemies.
- Reputation tiers unlock faction shops, faction cards and quest lines. Some factions are rivals: gaining with one
  lowers the other.

### X. Mounts and boats (depends on C; deepens with F)
- **Mounts** raise move speed and lower how often enemies notice the player. Bought or crafted (Woodworking carts,
  tamed beasts via quests).
- **Boats** cross deep water; some islands and their resources are only reachable by boat. Boats are crafted
  (Woodworking) and tiered.

### Y. Fishing and Cooking (depends on A and E)
- Two more skills. **Fishing** at water-edge nodes; **Cooking** turns fish and herbs into food.
- **Food heals life on the world map** between fights (the player's map life already persists). Higher-tier food
  heals more and can add a small next-duel buff through the blessing slot.

### Z. Contracts board (depends on A)
- Town boards offer bounties ("defeat 5 goblins", "deliver 20 iron bars"). Contracts **accumulate instead of
  expiring**: new ones are added over time up to a cap, and nothing is lost for not playing on a given day.
- Rewards: gold, XP, reputation, materials.

### AA. Optional story (last; content-heavy)
- A main questline per plane using the existing quest and dialog systems. **Always skippable**: a "skip story"
  choice at New Game and a per-chapter skip, so no character is forced through it again.

### AB. Opening paths: finish the tutorial guide's "Future release" options (depends on G, V)
The guide's first dialog (quest 28 "Entering Shandalar" in `Shandalar Ascendant/world/quests.json`) offers four
paths; two are disabled and marked "(Future release)". Turn each into a real starting path. Every path still
opens the whole game; the path only picks the first quest chain and a small starting bonus.
- **"Where am I? ..." (Tutorial and main quest)**: unchanged.
- **"I want to find the planeswalkers"**: a quest chain that tracks down the five color planeswalkers (the
  existing castle bosses), then leads into Planeswalking (V) and the optional story (AA). Skippable like AA.
- **"I want to make a name for myself"**: the competitive career. Starts at the nearest gym town with the gym
  challenge (G), introduces tournaments and the Grand Prix circuit (L), and ends at the League.
- **"Been here, done that" (New Game+)**: unchanged, plus the run-format choice from K.
- Remove "(Future release)" and `isDisabled` from the two options once their chains exist.

## Stretch goal: main program UI
After the systems above, improve Forge's main (non-Adventure) UI. Scope to be decided later.

## The Multiverse (see `Game-Vision.md`)

These packages turn the single big map into a home plane plus an endless chain of set planes. They supersede
**V (Planeswalking)** and absorb **N (Rifts)** as one delve type.

### MV1. Multi-plane save and planar portals (depends on C)
- One save holds several worlds: the **home plane** and any number of **set planes**, each a `World` generated
  from its own plane config and seed. Only the current plane is loaded; the rest stay serialized in the save
  (or in side files next to it) and load on demand.
- **Planar portal** objects (reuse `PortalActor`) move the player between planes, keeping position per plane.
- The player's collection, skills, materials and items are account-level; plane state (enemies, POI changes,
  nodes) is per plane.
- Old Ascendant saves load as a single home plane.

### MV2. Plane-per-set generator (depends on MV1)
- Generate a set plane from a template (smaller map than the home plane, e.g. 300-400 tiles) plus the set's data:
  the plane's format chosen in the portal dialog (K), biome mix from the set's color balance, enemies with `$generate` decks restricted to that set, shops and rewards
  from that set, town names themed per set.
- **Alignment**: planes of the sets in the Standard window are reachable by a normal portal. Mastering a set
  (existing mastery) unlocks the next set's plane. Rotated-out planes stay reachable through a costlier portal.

### MV3. Home fortress (depends on MV1, FT1)
- The first fortress is the **prime base**: stations, storage, vault, trophy room, outposts. It never resets except
  by prestige. See the FT packages and the fortress limits in FT1.

### MV4. Delves (depends on MV1, B; absorbs N)
- Temporary **pocket-plane portals** spawn at random in set planes. A delve is a chain of small instanced floors.
  Deeper floors: harder enemies, tier 4 nodes, gems, relics, guardians.
- Delve types: **resource delve** (nodes and guardians), **rift** (the roguelite run from N) and **Horde** (below).
- **Horde delve (co-op first):** an arena floor where the players face a pair of enemies at once; whenever one dies a
  fresh one takes its place, until the players tap out (concede) or lose. Waves get harder and enemies come from the
  plane's themes (EN1/EN2). Rewards scale with enemies beaten and the wave reached; banking rewards between waves is
  allowed so tapping out is a choice. Solo is allowed (one enemy at a time). Best runs go on a co-op leaderboard in the
  Hall of Fame (L).
- Track deepest floor per account for the Hall of Fame and prestige XP.

### M update. Prestige XP
Prestige XP is earned from how far the account went before prestiging: deepest delve floor, planes mastered,
League titles, total level. Spent on the prestige talent tree. Requirements stay steep.

## Co-op: shared world (see `Game-Vision.md`)

Forge already has what the duel side needs (`forge-gui/src/main/java/forge/gamemodes/net/`): a Netty server/client,
host-authoritative matches where remote humans play through `RemoteClientGuiGame` / `NetGameController`, lobbies
with team slots (`ServerGameLobby`, `LobbySlot.team`, default port 36743), and mobile online screens
(`forge-gui-mobile/src/forge/screens/online/`). Two humans on team 0 against an AI is already supported by
`GameLobby`. Adventure's `DuelScene` builds matches locally with `MatchController.startMatch(..., guiMap, ...)`, so a
remote human can be added through `guiMap`. Nothing in Adventure knows about a second human yet.

### CO1. Session and connection (start now)
- **Host / Join** from the Adventure main menu (Ascendant only). Join takes an address (Tailscale `100.x` or LAN).
- **Two ports**: Forge's game port (36743, existing) and a new overworld port (36744). Document the Windows firewall
  rules; skip UPnP when the address is a Tailscale address.
- **Hard version check**: co-op refuses to connect unless both builds and card databases match (the existing login
  only warns). Exchange a build hash plus a hash of the loaded card data.
- **Characters**: the guest brings their own character file (their `AdventurePlayer`: collection, decks, skills,
  materials, items) and keeps it on their own PC; it is saved locally when the session ends. The host's save owns
  the world.
- **World**: the guest builds the host's world from the host's world seed and plane config (generation is
  seeded), then checks a hash of the result against the host's. On mismatch, fall back to receiving the world data.
- **Messages**: a small set of `NetEvent` types over the existing Netty pipeline (add them to `WireClassFilter`),
  or JSON via Gson. Never send `PaperCard`/`Deck` objects; send decklists as text (`DeckSerializer`).

### CO2. Shared overworld (depends on CO1)
- The client sends position and facing at 10-20 Hz; the other player is drawn as a partner sprite with name tag.
- **Host-authoritative world state**: enemy spawns and movement, resource nodes (first to gather claims it), POI
  changes (cleared enemies, opened chests), loot rolls. The guest sends requests; the host confirms.
- **Party up or go separate ways**: players are independent by default. Either can invite the other to a **party**
  and either can leave it at any time. Nobody is ever moved, pulled into a location or pulled into a fight without
  saying yes.
- **Locations, v1**: both players roam freely. Entering a town, dungeon or delve asks a party partner who is nearby
  whether to come along; declining is fine. Players in different interiors is a later step (v1 may require the
  guest to wait outside an interior the host is in, or vice versa).
- Pausing menus (inventory, deck editor) don't pause the world in co-op.

### CO3. Co-op duels (depends on CO1; can run alongside CO2)
- **Joining is opt-in**: when a player starts a fight and their party partner is nearby (configurable radius), the
  partner gets a "Join the fight?" prompt with a short timer. No answer or not in a party means a normal solo fight.
  Not in a party: never prompted.
- For a joined fight the host builds the match as `DuelScene` does, with the
  host on team 0, a second `RegisteredPlayer` for the guest on team 0 (deck rebuilt from the decklist text, GUI from
  `FServerManager.getGui(slot)` / `RemoteClientGuiGame`), and the enemies on team 1.
- **Scaling**: enemy life and extra cards scale for two players (tunable, like the existing enemy tuning).
- **Adventure setup on the host for both players**: equipment and perk effects, custom card scripts, mana shards,
  badge perks. Avatars are sent as names/ids, not textures.
- The guest plays on the standard mobile match screen through `FGameClient`. When the match ends the host sends the
  result; each player applies their own rewards, XP and penalties to their own character.
- Watch out for blocking `sendAndWait` prompts freezing the host while the guest decides; keep timeouts generous.

### CO5. World-bound partner characters (the Stardew "farmhand" model; decided 2026-10-09)
**Amendment (2026-10-09, Steve): co-op worlds are their own saves.** Hosting only happens from a **co-op world**: a save
created as co-op (New Game option) or converted once from a fresh world. Both players' characters for that world live
in its save on the host's PC, including the **host's own co-op character**, which is separate from the host's solo
characters. Progress is made together in that world. Solo saves of both players never enter co-op and never receive
co-op progress. The partner rules below apply to the guest's character in that world.
Replaces the CO1 guest save model (guest `.chr` files, stashing and restoring the guest's solo save, character-id
migration). **Why:** a guest who brings their solo character carries their own rotation and set progress into a world
whose rules belong to the host, so gyms and formats keep clashing, and every guest save path is a distributed-save
problem. A co-op character that lives in the host's world removes both.
- **A partner character belongs to one world.** The first time a guest joins a host's world, they create a character
  for that world (name, look). It is stored **in the host's world save** (a `partners` map keyed by the guest's
  profile id), follows that world's rotation and plane formats, and is waiting for them on every later join. A guest
  can only play it while that host is hosting, as in Stardew.
- **Solo characters never enter co-op.** The guest's solo save is never read, written, stashed or restored by a
  session. Leaving a session returns the guest to the main menu; their solo game is exactly as they left it.
- **Profile id:** a stable id generated once per install and stored in the user folder (not per character). One
  partner character per profile per world.
- **Starter gift (tunable):** a new partner character starts with a sealed pool from the world's current sets, like a
  new game. Optional host setting: also let them copy one deck from their solo character as a gift.
- **Host is the source of truth.** On join the host sends the partner character. During the session the guest plays on
  a local copy and sends a full snapshot to the host after every duel, on gather/craft batches (debounced, e.g. every
  30 s of play) and on leave; the host stores it in the world save with its normal saves. A guest crash loses at most
  the last few seconds; a host crash loses the same as the host's own progress.
- **Legacy import:** a guest who already has a co-op character from the old model (`characters/<name>.chr` or
  `characters/guest/<id>.chr`) is offered a one-time "bring your existing co-op character into this world" on first
  join after the update, so nobody loses progress.
- **Rules:** partner characters use the host world's rotation and, with K, each plane's format. Ordinary overworld
  co-op fights accept any deck (only the game-wide restricted, joke and digital-only cards are refused); gyms, the
  League and tournaments check each deck against the plane's format and list any illegal cards before the fight
  instead of dropping the player.
- **Remove after migration:** the guest `.chr` store, `stashGuestSave` / `restoreGuestSave`, the guest-side
  `blocksLocalWorldSave` session gating, and character-id migration code. Keep the `forge.test.userDir` test isolation.
- Tests (temp user dir): first join creates a partner in the host save; leave and rejoin restores it with progress;
  guest crash mid-session loses only the last snapshot window; the guest's solo save file is byte-identical before and
  after a session; legacy `.chr` import; two different guests get two partners; a host with two worlds keeps separate
  partners.

### CO4. Co-op systems (depends on CO2, CO5, MV3)
- Shared home base on the host's home plane: both can use stations, storage and outposts.
- Trading cards, materials and items between players (TR1).
- Co-op gyms and League (both must win), co-op delves, shared quest progress.

### CO6. Co-op mechanics outside fights (depends on CO5; each item can be its own PR)
Things that make playing together better than playing side by side.
- **Assisted gathering:** two players channelling the same node gather faster and get a bonus roll. **Great nodes**
  (an ancient ironwood, an ore motherlode, a leviathan carcass) spawn rarely, have lots of hits, and are really meant
  for two (solo works but is slow).
- **Combined crafting:** top-tier recipes with two steps at different stations done by different players (one forges
  the base, the other enchants or inscribes it), so specializing pays off. Recipes say which skills each step needs.
- **Shared quest log:** party quests give both players credit; some town and friendship events need both present.
  Townsfolk remember the pair.
- **Rescue run:** when one player loses a fight, the other can go fight that enemy to recover the lost gold or items
  (a timed marker on the map).
- **Tag-team gyms and League:** badge fights as a pair (both must win, or a 2HG match once K supports it).
- **Map co-op:** shared pings ("come here"), shared bookmarks and waypoints, partner shown on the minimap.
- **Fortress together:** both build, store and defend (FT3 raids side by side); a partner cabin or quarters in the
  host's fortress (see the fortress design).
- **Shared Archive progress:** both players' friendships feed the same world's Archive and Bell case.
- Tests per mechanic with two players over loopback.

## Fortresses (see `Game-Vision.md`)

Replaces the homestead. War-torn frontier strongholds, built and defended.

### FT1. Claim a site and the fortress instance (depends on E)
- **Construction** skill added to `PlayerSkills.Skill`, with a talent tree.
- **Banner** item: on the overworld, valid only on walkable land at least N tiles from towns and other POIs. Planting
  it creates a fortress POI (new `type: fortress`), saved in the world's POI data. **Fortress limits** (2026-10-08):
  - **One prime base** per account, the first fortress (home plane). Only it can build the vault, trophy hall,
    Hall of Remembrance and top-tier stations (mark these `primeOnly` in `structures_fortress.json`).
  - **One forward fortress per set plane**: portal anchor, fast-travel point and supply-line hub, with a tier cap
    (start at Keep) and a smaller structure list (walls, gates, towers, basic stations, storage that feeds the
    prime base's stockpile once LG1 exists).
  - Outposts (LG3) stay as lighter links and don't count against this limit.
- Entering loads an **instanced map**: a template `.tmx` per biome (open ground, a buildable zone marked by an
  object layer, an entry/exit). The template is the base; player structures are stored separately in the save and
  added on load.
- **Build mode**: a grid cursor (keyboard, mouse and controller), a structure picker, footprint preview (green/red),
  rotate, demolish with partial refund. Structures are data (`world/structures_fortress.json`: id, footprint,
  sprite/tiles, cost, Construction level, tier, effects, station type).
- Stations built here are the same stations as in towns (Forge, Workshop, Apothecary, Jeweler, Spell Smith,
  outposts' storage).

### FT2. Tiers and structures (depends on FT1)
- Fortress tiers: Camp, Palisade, Keep, Castle, Citadel. Upgrading costs bulk materials and needs Construction
  levels; each tier enlarges the buildable zone and unlocks structure tiers.
- Structure set (first pass): wooden/stone walls and gates, watchtower, barracks, storage, war room (quests and
  contracts), training yard (Dueling XP), library (deck tools), shrine (blessings), vault, trophy hall.
- Art: start with existing town tiles and sprites; replace with original art later.

### FT3. Raids and sieges (depends on FT2)
- Warbands raid fortresses on an in-game timer, scaled by fortress tier and stored wealth, announced in advance.
  Raids only happen when the player is online, and a raid can be fought from anywhere on the same plane (travel
  there or accept at a distance with a penalty).
- A raid is a series of duels. Defenses change the duels: walls add starting life, towers add starting creatures or
  damage, gates reduce the number of waves, barracks garrison adds an AI ally (team 0).
- Big sieges use the Archenemy format.
- Losing damages structures (repairable with materials) and takes a capped share of stored goods. Never total loss.
- Co-op: both players can defend a shared fortress together (uses CO3).

### FT4. Territory (depends on FT1, B2)
- Each fortress has a control radius on the overworld: more gathering nodes, weaker roaming enemies, linked
  outposts, and a fast-travel anchor. On other planes (MV1) a fortress is the plane's portal anchor.

## Logistics, trade and the Invasion (see `Game-Vision.md`)

Scope order: **TR1 first** (needed for co-op), then LG1, then the rest.

### TR1. Player trading (depends on CO1, CO5)
- Trade window between connected players: each side offers cards, materials, items and gold; both confirm; the
  exchange is applied on both characters at once. Vaulted cards and cards in decks can't be offered.
- **With CO5 both characters live in the host's world save, so a trade is one local, atomic operation on the host:**
  the guest's inventory is locked while the trade window is open and confirmed; the guest sends its current snapshot;
  the host applies the trade to its own character and the partner record, saves the world once, then sends the
  partner its updated character. If the guest drops before receiving it, the next join delivers the stored copy, which
  already includes the trade. No escrow, reconcile log or two-phase protocol.
- Later: offline trade codes (export an offer, the other player imports and accepts).

### LG1. Auto-sorting storage and workers (depends on FT1)
- All storage in a fortress forms one sorted stockpile; stations and crafting read from it directly.
- Workers are hired NPCs assigned to buildings: haulers (move goods between fortress, outposts and linked towns),
  refiners (turn raw materials into refined or dust on a standing rule), smiths (craft standing orders).
  Worker count and speed grow with fortress tier and a new skill or Construction perks.

### LG2. Supply lines and town relations (depends on LG1, MV1)
- **Town relations**: reputation per town; allied towns trade and accept supply lines; conquered towns (via a
  siege of their defenders) pay tribute and can rebel if neglected.
- **Supply lines**: assign caravans between your fortress, outposts and allied or conquered towns, on any plane you
  have a portal anchor on. They move goods on in-game time and can be raided (by the Invasion, see WAR1).
- **Far-plane stock**: a supply line to a town on another plane stocks your fortress shop with that plane's cards.

### LG3. Outpost network and intel (depends on B2, LG2)
- Linking outposts (a road or courier between them) lets them trade stock with each other and with your fortress.
- Linked outposts report intel to the war room: raid warnings, invasion front movement, rare node and delve
  sightings.

### WAR1. The Nothing (depends on FT3, MV1; lore in `Lore.md`)
- **Trigger**: the Nothing starts appearing after the player masters their **first Standard set**.
- **It eats the map**: a per-plane **void mask** (chunks or tiles) saved with the plane. Voided land is drawn as
  nothingness, blocks movement, removes nodes, roads and POIs, and erases towns (shops and quests gone). The
  `WorldBackground` chunk renderer and spawn code must respect the mask.
- **Breaches** spread the void on in-game days while playing (never while away); fortresses and outposts resist
  or slow it nearby.
- **Soulless minions** with their own enemy decks and look; **commanders** per front; **breaches** to close.
- **Investigation**: each plane holds clues (from commanders, closed breaches, intel from linked outposts). Enough
  clues across planes reveal where the source is. Endgame: expose the Fallen's operation.
- **Erasure is permanent**: voided land, nodes, roads and towns never return. Closing a breach stops its spread.
  **Fortresses are never erased**, only besieged (FT3); the land around them can be.
  Pace is a tunable so permanence stays fair.
- **Transit nodes**: the Nothing builds nodes near planar portals to link planes (the Fallen's plan). Linked nodes
  speed its spread to new planes; destroying them yields clues and slows it. These are each plane's main objective.
- **Watcher**: named at character creation; acts as guide, scout and intel voice (raid warnings, breach reports,
  investigation hints).
- Story through the war itself, with skippable dialog. Player is a Death Watch Initiate with a Watcher (guide) and
  a Supervisor (co-op players share one).

## Inventory overhaul

### INV1. Inventory screen and bags (no hard dependency; do before more item content)
Today the inventory is one flat list of every item (equipped ones included) under a large tooltip panel. Replace it:
- **Layout**: keep the equipment paper doll on the left. On the right, a **tabbed bag area** takes most of the
  space; item details shrink to a compact panel (or a hover/selection tooltip).
- **Equipped items appear only in their slot**, never also in the bag list. Unequipping puts them back in the bag.
- **Tool slots**: a toolbelt row on the paper doll with one slot per gathering family (axe, pickaxe, sickle,
  chisel, bucket/still, salvage tool). Tools equip there, not in the bag.
- **Bags by type** (tabs):
  - **Backpack**: gear and usable items, slot-based with stacking for stackable items.
  - **Packs**: unopened card packs and boosters, in their own storage.
  - **Currency pouch**: gold, shards, the four dusts, and contest coins (gym, tournament and Grand Prix currency),
    with room for future currencies.
  - **Materials**: raw and refined materials (the existing Materials tab), stack-based.
- **Capacity grows, no weight**: each bag has a slot count and a max stack size. Upgrades (crafted or bought, e.g.
  bigger backpacks, pack satchels, material sacks) add slots and raise stack sizes. Nothing ever has weight.
- **Never refuse, never delete: the Overflow stash.** Anything that doesn't fit goes to an **Overflow** tab (warning
  badge, saved). Overflow items can't be used, equipped or sold until moved into a bag with room. Quest items are
  exempt from capacity. Old over-capacity saves load straight into Overflow.
- **Being overloaded has consequences** while anything is in Overflow:
  - **Slower movement**, scaling with how much is overflowing, down to about half speed.
  - **No fast travel**: no inn waypoint travel and no planar portals until Overflow is empty.
  - **Overflow cap of 10 slots.** Past it, new items are auto-sold at normal sell price (cards to dust instead),
    never silently deleted. No timer.
- **Craft Pouch (deep slots for gathered resources).** Gathered and crafting materials never use the backpack;
  they go to a Craft Pouch with far bigger stacks (like No Man's Sky cargo slots or ESO's craft bag):
  - Satchel (start): ~20 slots, stacks of 250.
  - Pack: more slots, stacks of 500.
  - Hauler's Sack: more slots, stacks of 1,000.
  - **Bottomless Craft Bag**: unlimited slots and stacks, materials only.
  - **Craft Pouch tiers are NOT craftable.** They come only from the **Mastery Surge** (below). Every other bag
    upgrade (backpack, pack satchel, currency) is crafted from gathered materials.
  - Stations and fortress storage pull straight from the Craft Pouch. Each co-op player has their own.
- **Mastery Surge.** Completing a set mastery releases a burst of power: the player picks **one or two upgrades**
  (tunable) from a short list. The Craft Pouch's next tier is always on the list. Other first-pass options
  (tunable, data-driven): +1 duel perk slot, +1 tool enchant socket, +1 Overflow slot cap. Unspent picks are kept
  until used.
- **Compare**: selecting (or hovering) a bag item that fits an occupied slot shows it side by side with the
  equipped item, with each stat difference marked better/worse (green up, red down): life, starting cards, move
  speed, gold, card rewards, mana shards, mulligans, start-of-battle cards, opponent effects, enchant sockets,
  tool tier and gathering effects. Works for gear, tools and jewelry; a "Compare" button (controller: hold Y)
  can also pin any two items against each other.
- **Controller-first**: D-pad moves across slots and tabs, A selects/equips, X uses, Y shows details,
  shoulder buttons switch tabs.
- **Icons**: many items reuse unrelated sprites (a sword icon for a pickaxe, and so on). Give every tool,
  material, pack and currency a fitting icon, starting from the Kenney/LPC packs in `A:\GameAssets` (see
  `CREDITS.md`) or Steve's own art.
- Save: bag contents and capacities saved per bag; old saves auto-sort the current flat inventory into the new bags.

## Duel screen

### DS1. Modern duel screen in libGDX (after INV1)
Improve the existing libGDX match screen (`forge-gui-mobile/src/forge/screens/match/`) instead of switching UI
toolkits, so co-op duels (`RemoteClientGuiGame`), Android and macOS keep working. Reference: **Neo Forge**
(<https://github.com/AdrianLopez98/NeoForge>, GPLv3, JavaFX) has solved these as behaviour; study how it does each
(`forge-gui-neo/.../ui/CombatOverlay.java`, `TableScreen.installDragGestures`, `match/NeoMatchUI.onCardDropped`),
reimplement in libGDX, and credit Neo Forge in `CREDITS.md` for anything adapted from its code.
All input still goes through `getGameController().selectCard/selectPlayer`, so it works over the network unchanged.
- **Combat and target arrows**: curved arrows from card edge to card edge, colour-coded (red attacks, blue blocks,
  amber targets), drawn on a click-through overlay that redraws while cards move.
- **Drag to cast / drag to attack and block**: a small drag threshold (~9px) so plain clicks still work. Drop from
  hand = cast; in combat, drop an attacker on a player/planeswalker or a blocker on an attacker; dragging a permanent
  outside combat does nothing. The arrow follows the drag.
- **Press-to-peek hand**: hold on a hand card to enlarge it, slide to the next card, push up onto the table to play.
- **Phase stops**: clickable phase rail to set where the game stops for you (ties into the auto-yield fixes).
- **Clickable floating mana**: mana pool shown as clickable symbols near your field.
- **Drag to reorder the hand.**
- **Controller**: every action above also has a controller path (focus cursor, A to pick up/drop, B cancel).
- Works in solo and co-op duels and in stock Forge matches on the mobile/libGDX client; gate anything that changes
  stock behaviour behind a preference defaulting to the new UI only in Ascendant until it's proven.

### DS2. Clear counter and fizzle banner (small; Ascendant duel screen and the classic one)
- When a spell or ability **you** control is countered, show a banner in the middle of the duel screen with both
  cards side by side: "Your Lightning Bolt was countered by Cancel". Same banner when your spell or ability fizzles
  because its target became illegal ("Your Lightning Bolt fizzled: its target is gone"), and when an opponent's
  spell is countered by yours (shorter, quieter).
- Stays about 3 seconds or until clicked/tapped; never blocks input; queued if several happen at once.
- Driven by Forge's game events (countered/fizzled), not by parsing the log. Works for the LLM opponent and in co-op
  (each player sees banners for their own spells).
- Tests: a countered spell and a fizzled spell each raise exactly one banner event for the right player.

## AI opponent: bring your own

The LLM opponent (`forge-ai/.../llm/LlmOpponent.java`, settings in `%APPDATA%\Forge\llm_opponent.properties`)
stays optional and never ships a key. Forge's normal AI is always the fallback.

### AI1. Player setup guide and settings screen
- A settings screen for the LLM opponent (enable, endpoint URL, model, API key, timeout, test button) instead of
  hand-editing the properties file. The key is stored locally and never logged or shown in full.
- A thorough guide in `docs/Adventure/AI-Opponent.md` covering:
  - **Hosted, OpenAI-compatible APIs** (DeepInfra, OpenRouter, etc.): where to get a key, rough cost per match,
    recommended models.
  - **Local models, no key**: LM Studio, Ollama or llama.cpp; recommended sizes by GPU memory; AMD cards via the
    Vulkan backend (works on Windows), NVIDIA via CUDA.
  - Troubleshooting: timeouts, slow turns, falling back to Forge AI.
- Use the LLM only for key decisions (attacks, blocks, main spells); Forge AI handles routine priority passes, so
  local models stay fast.

## The core loop: living towns, friendship, the Archive and Bells (see `Lore.md`, `Game-Vision.md`)

From the 2026-10-08 design handoff (`Handoff-2026-10-08.md`). The loop: **befriend townsfolk (mostly by playing
Magic with them) → archive their town's culture → that knowledge earns the town a Harmonic Bell → the Nothing still
eats the land, but not the people.** These are milestones 5-7.

**Coordinate with Tiny first:** he is building a travel and communication system (research system + lore pass).
Anything touching shipments, roads or messages between towns (LT1 shipments, LG2 supply lines) must be designed
with him.

### LT1. Living towns (milestone 5; Steve builds the first town map by hand)
- **Hand-made town pipeline**: towns authored in Tiled with the Kenney 16px tilesets (`maps/tileset/kenney_*.tsx`)
  and Forge's object templates (`maps/obj/*.tx`). A starter template lives at
  `common/maps/map/ascendant/starter_town.tmx`. Hand-made towns are referenced from Ascendant's
  `points_of_interest.json` like any other town.
- **Civic buildings**: homes, church or temple, town hall, tavern, school, graveyard. Every non-shop building is an
  interactable map object that feeds the Archive (AR1). Each set plane flavours them (temples on a Theros-style
  plane, guildhalls on a Ravnica-style plane).
- **Townsfolk**: named NPCs (data: `world/townsfolk.json` — id, name, town, home, daily spots, portrait/sprite,
  personality notes, deck, signature card, gift likes/dislikes). They stand at their spots on the town map and can
  be talked to, played with (FR1) and given gifts. Daily schedules come later with Seasons.
- **Every town has a general store.** **Shops restock by shipment**, not magic refresh: stock arrives from elsewhere
  (towns, trade routes); a cut road means towns past it run short. Design with Tiny's travel system.

### FR1. Friendship through Magic (milestone 5)
- **Every townsperson has a deck that says who they are** (data-driven deck file per NPC; e.g. the smith's sturdy
  artifacts, the priest's lifegain and protection, the kid's janky pile).
- **Casual games** at the tavern or on the porch: no life loss, no loot, no penalties; a friendly match that raises
  friendship (more for close or fun games, a little even for losses).
- **Friendship levels** per townsperson (e.g. 0-10 hearts) from games, gifts and conversation. Levels unlock
  dialogue, keepsakes for their alcove, Archive entries, and small favours.
- **Top reward: their signature card**, added to your collection; a copy sits in their alcove. If their town is
  lost, it's one of the last pieces of them.
- Co-op: each player has their own friendships; casual games can be played by either player.

### AR1. The Archive and the Hall of Remembrance (milestones 5-7)
- **Archive** substation in the fortress (FT1). Archive entries per town: people known, culture recorded from
  civic buildings, festivals, crafts, stories. A per-town **knowledge score** drives Bell eligibility (BL1).
- **Hall of Remembrance** (pocket dimension reached from the fortress): the Endless Hall with one door per visited
  plane; each plane's room has one alcove per town (procedurally generated; special towns can get hand-made
  rooms). Alcoves fill with keepsakes, recipes, banners, voices, signature cards, and every person in the town.
- **Fast travel = the Hall**: from the fortress, a quick menu merges you into any town you have an alcove for
  (replaces/extends the inn waypoint travel from C). Or step into a town's **echo**: the town's real map with a
  ghostly filter and a snapshot of its people, where you can see each relationship and then merge into reality.
- **Lost towns**: alcove sealed (nothing more can be added), no fast travel, echo stays walkable, frozen at the
  last moment you knew it. **Bell-saved towns** get their own mark.
- **Co-op**: each player has their own Hall. Guest visits (look only) to a partner's Hall: feasible and clean
  (same kind of host-world load as CO2, nothing syncs back); build after the Hall exists.

### BL1. Harmonic Bells and the Watcher's warnings (milestone 7, with WAR1)
- The Watcher reports the Nothing's approach per town ahead of time ("eight days at current pace").
- The contingent can anchor only so many Bells per season (tunable). A town is **eligible** once its Archive
  knowledge passes a threshold (tunable; open question in `Lore.md`). The player chooses which eligible towns get
  Bells; Engineers anchor each at a ritual site (a short quest or event in the town).
- When the Nothing takes a Bell town, its land is erased but its people "go home": the alcove gets the saved mark,
  townsfolk data is preserved (whether they can be met again is an open question).
- You can't save everyone; the choice is the point.

### SE1. Seasons (milestone 6)
- **Each Standard window is a season.** An in-game calendar with days; rotation happens at season end. Planes of
  rotated sets drift out of alignment (MV2).
- Seasons drive townsfolk schedules, festivals (Archive entries), the Nothing's pacing (BL1) and Bell allotments.

### CH1. The Supervisor and the Watcher
- Both nameable at character creation with default names offered (defaults still to be chosen).
- **Supervisor**: gruff because he cares; scripted "slips" (draft lines for solo and co-op in `Lore.md`); co-op
  players share one Supervisor (host's name).
- **Watcher**: spy, advisor, scout; starts literal and alien and gradually talks like someone who knows you
  (dialogue lines keyed to playtime, friendships and events). Voice of raid warnings, Nothing reports, intel.

## Formats, achievements and card styles (2026-10-08)

### EN1. Enemy decks by format and theme (depends on K for the plane format; data can start now)
- Enemies carry **theme tags** by creature type (merfolk, goblin, zombie, ...). Each tag has **2-3 themes**: for
  merfolk, e.g. merfolk tribal, krakens/octopus, leviathans. A spawned enemy picks one theme at random and keeps
  it, so not every merfolk plays the same deck.
- Each theme has a version for **every format**: Historic, Pauper and Commander are fixed deck lists (several per
  theme where possible). **Bellwarden Standard** versions are built from a **theme recipe** (colors, creature
  types, key cards and mechanics to look for) filled from the sets in the current Standard window, because fixed
  Standard lists go stale on rotation.
- 2HG: co-op fights pair two themed decks of the plane's format.
- Data: `world/enemy_themes.json` (theme id, tags, colors, recipe) plus deck files under
  `decks/enemy/<theme>/<format>_N.dck`. If a theme has no deck for the format, fall back to the nearest one and
  log it; never crash.
- **Important fights use real, hand-picked lists**: gym leaders and trainers, the League, the Nothing's commanders
  and story fights. Generated lists are for ordinary overworld enemies.
- **Test**: every fixed deck is legal in its format (Forge's format checks; Commander via its deck rules: 100
  cards, singleton, color identity), and every Standard recipe produces a legal deck for the current window or
  falls back cleanly.
- First batch: about 8 common enemy types with 2 themes each (merfolk, goblins, zombies, elves, vampires,
  dragons, soldiers/knights, spirits). Grow the library over time.

### EN2. Co-op enemy partners (depends on EN1, CO3)
- **In a co-op duel the enemy brings a partner of the same creature type, playing a different theme.** A merfolk brings
  a second merfolk on another merfolk deck; a goblin tribal enemy brings a goblin burn partner. Two players face two
  enemies, so the fight feels like a pack, not two decks ganging up on one.
- **Every creature type needs at least two themes with distinct decks in every format**, so a pair never mirrors.
  Today merfolk and the sea monsters (kraken_leviathan) have one theme each: add a second merfolk theme (e.g. merfolk
  tempo or mill) and a second sea-monster theme (e.g. serpents and big leviathans). Add a test that every creature-type
  tag has two or more themes.
- **Pairing rule:** the partner uses a different theme from the same tag, picked deterministically from the encounter
  so host and guest agree. Fallback when a type has only one theme, or for enemies with no theme tag: a random themed
  enemy from the same biome. Bosses, gym and League fights, and encounters that already chain enemies (`nextEnemy`)
  keep their hand-made setups.
- **Scaling:** with a partner, drop the single-enemy boosts (`coopDuelEnemyLifeFactor` back to 1.0 and no extra card).
  Keep both settings tunable, and keep the boosts for the fallback case where no partner could be built.
- **Seats:** team-0 the two players, team-1 the two enemies, each with their own life and turns. A Two-Headed Giant
  (shared life) option per format comes with package K.
- Rewards: each player rolls loot as for their own kill of one enemy (tunable), so a pair isn't worth double.

### RW1. Fight rewards feed the current set (depends on EN1; uses CS0 printings)
Today a win against a themed enemy gives cards from the enemy's own deck, which are often Historic or off-theme and do
nothing for the player's current set progress.
- **One guaranteed signature card** from the beaten enemy: picked from that theme's hand-picked core
  (`world/enemy_cores/<theme>.json`) that appear in the deck it played, so it feels like *that* enemy's card. Weighted
  toward cards the player doesn't own yet.
- **The rest of the card rewards come from the current set**: the set plane the player is on, or on the home plane the
  newest set in the current rotation. Same rarity rolls as today. These count toward set mastery and achievements.
- Printings follow CS0 (the signature card uses a normal printing from the rotation if possible; set cards use that
  set's printing).
- Gold, dust, materials and item rewards unchanged. Gyms, League, bosses and quest rewards keep their own tables.
- Co-op (EN2): each player gets their own signature card (from the enemy they're credited with) and their own
  current-set cards.
- Tunables: signature count (default 1), current-set share of the remaining card rewards (default 100%).
- Tests: a win against a merfolk_tribal enemy on a ZEN plane gives exactly one merfolk core card plus ZEN cards; on the
  home plane the extra cards come from the newest rotation set; stock world unchanged.

### AC1. Achievements (no hard dependency)
- **Account-wide, stored outside the save** (next to the Hall of Fame and prestige data) and **kept through
  prestige**.
- Data-driven `world/achievements.json`: id, name, description, category, condition (counters and game events),
  reward (title, card style, trophy, cosmetic). Unlock toast plus an **Achievements screen** reachable from the
  status menu, controller-friendly.
- Categories: collection, gyms and League per format, delves, fortresses, friendships, skills (99s), co-op,
  the Nothing.
- **Set completion**: owning every card in a set (each distinct card in the set's main card list) grants a trophy
  for the trophy hall and an exclusive style for one of that set's cards.
- **Completing every set in Bellwarden** grants something unique: a title, a card style no other source gives and
  its own Hall of Fame entry.
- Forge has an achievement system in its other game modes; reuse its pieces only if they fit cleanly.

### CS0. Printings come from where you got them (small, do first; Ascendant only)
- Every card the player receives uses the printing from its source: a pack gives that pack's set printing, a set
  plane's rewards and shops give that set's printing, a shop gives the printing from its own set pool. Anything with
  no set context (generic loot, junk shops) uses the card's normal printing from a set in the player's current
  rotation, falling back to its most recent normal (non-promo, non-showcase) printing.
- No random variants anywhere in Ascendant: ignore the `useAllCardVariants` setting for rewards, shops, packs and the
  Spell Smith. The stock world keeps today's behaviour.
- Tests: rewards, shop stock and pack contents from a ZEN source are all ZEN printings; a junk-shop card uses a
  printing inside the rotation; `useAllCardVariants=true` changes nothing in Ascendant.

### CS1. Card styles: alternate arts, special versions and foils (depends on CS0; AC1 for some sources)
- **A style is cosmetic, not a different card.** Alternate art, showcase, borderless, foil and reprint art from other
  sets are styles unlocked per card name (printing/set code, art index, foil). An unlocked style can be applied to
  any copy the player owns; it never changes the card, its legality, sale price or crafting value.
- **Account-wide and kept through prestige**: a style applies as soon as the player owns that card again.
- **Deck editor and collection**: pick which unlocked style each card shows (Forge already stores a set and art per
  card in a deck).
- **Three ways to get styles:**
  - **Buy**: a cosmetics vendor in towns (an artist or engraver) sells styles for gold or shards; stock rotates.
  - **Craft**: dust plus a rare cosmetic material (e.g. "prismatic ink" from delves or achievements) crafts a chosen
    style, so a specific chase is possible.
  - **Earn**: achievements (AC1), quests, card mastery (T) foil and alt-art unlocks, and friendship rewards (e.g. a
    townsperson's signature card in their own art).
- Packs may still show a small chance at a foil; pulling one unlocks that foil style.

## Suggested order

1. A (materials core) alone. A2 and B2 after B lands the new material lines.
2. B, C, E, G and J in parallel. C touches world generation only; G touches town maps and duel setup;
   J touches `PlayerSkills` and the Skills screen.
3. D and F after their dependencies; K after G.
4. L after K, then H.
5. RPG systems, in this priority: N (Rifts), R (Ironman), O (world bosses), P (companions), V (Planeswalking),
   then Q, S, T, U, W, X, Y, Z. AB once G and V exist.
6. M (prestige) and I (homestead) once the account-level systems exist.
7. AA (story) last.
8. Multiverse: MV1 → MV2 and MV3 → MV4. See `Game-Vision.md` for milestone order.
9. **Co-op is next**: CO1 first, then CO2 and CO3 in parallel, then CO4.
10. Fortresses: FT1 → FT2 → FT3 and FT4.
11. TR1 with co-op; then LG1 → LG2 → LG3; WAR1 after FT3.
12. INV1, then DS1 (duel screen).
13. EN1 data and AC1 can start now; K after MV2; CS1 after AC1; EN2 after EN1.
14. Core loop: LT1 + FR1 (first living town) → AR1 → SE1 → BL1 with WAR1; CH1 alongside.

## Art

Every material, tool, potion and gear piece needs an icon. Start with recolored existing sprites from
`sprites/items.atlas`. Free CC0 pixel-art packs are license-safe for later replacement. Do not block a package on art.
