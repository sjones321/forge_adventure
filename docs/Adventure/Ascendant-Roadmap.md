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
- Map rework and new world locations only affect **new games** (the world is generated at New Game). Gyms and
  everything else must work on existing saves.

## Skills

| Kind | Skill | Source / output |
|---|---|---|
| Gathering | Woodcutting | Forest nodes → logs |
| Gathering | Mining | Mountain nodes → ore, gems |
| Gathering | Quarrying | Plains nodes → stone, marble |
| Gathering | Foraging | Swamp nodes → herbs, bone |
| Gathering | Delving | Island nodes → crystal, pearls |
| Gathering | Salvaging (existing) | Wastes nodes → scrap, relic parts (in addition to its card role) |
| Crafting | Smithing | Ore → bars → weapons, armor |
| Crafting | Woodworking | Logs → bows, shields, staves |
| Crafting | Alchemy | Herbs → potions (one-duel boosts) |
| Crafting | Jewelcrafting | Gems, crystal, pearls → rings, amulets |
| Crafting | Spellsmithing (existing) | Cards, and **refining any material into dust** |

Each family has four tiers, needing levels 1 / 15 / 40 / 70 to gather and to use in recipes:

| Family | T1 | T2 | T3 | T4 |
|---|---|---|---|---|
| Logs | Oak | Willow | Yew | Ironwood |
| Ore | Copper | Iron | Mithril | Adamant |
| Stone | Rough stone | Granite | Marble | Starstone |
| Herbs/bone | Nightshade | Grave moss | Bone | Wraithbloom |
| Crystal | Quartz | Azure | Prismatic | Aether |
| Scrap | Scrap | Brass gears | Thran parts | Thran relic |

Gems (Mining, any tier, rare roll): Garnet, Sapphire, Emerald, Ruby, Onyx. Boss materials: one unique per boss.

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

### B. Gathering on the overworld (depends on A)
- Add the 5 gathering skills to `PlayerSkills.Skill`.
- **Tools**: toolbelt in `AdventurePlayer` (one tool per family, saved). Tool tier caps the node tier you can gather.
  Tools are crafted (package E) or bought from general stores; T1 tools are given free at New Game and on old saves.
- **Nodes**: `WorldStage` keeps a `nodes` list parallel to `enemies` (`handleMonsterSpawn` ~285, `spawn` ~332).
  Spawn in the current biome on a timer, max ~4 alive, longer lifetime than enemies, never on colliding tiles.
  Tier odds weight toward the player's level. Saved/loaded with the world like enemies (~446-481).
- **Channel**: touching a node starts a 1-3 s channel with a progress bar; moving or an enemy collision cancels it.
  Yield 1-3 units by level; rare roll for gems or a little dust.
- Reuse existing sprites (biome decoration, `sprites/treasure.atlas`) with a tint per family. No new art required.

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
- **8 gyms**, each in a capital or major town, each testing a different way to play:

  | Gym | Format |
  |---|---|
  | White | Standard, best of 3 |
  | Blue | Sealed: 6 packs of a window set handed out at the door, build on the spot |
  | Black | Historic |
  | Red | Commons only, from your collection |
  | Green | Commander |
  | Colorless | Gauntlet: 3 duels in a row, life carries over |
  | Multicolor 1 | Booster draft against an AI pod (reuse the inn event draft code) |
  | Multicolor 2 | Highlander: 60-card singleton |

- Each gym has 2-3 trainer duels before the leader. Gyms can be done **in any order**; leader decks scale with the
  number of badges held.
- **Badges** give a perk each, unlock higher-tier crafting recipes, and are shown on the Skills/Unlocks screens.
  Rewards: gold, dust, a unique material, and a format-themed staple.
- **League**: with 8 badges, the Elite Four and the Champion at a central castle, best of 3 each, no healing between
  matches. Beating it unlocks rematches: leaders and the League return with harder decks and a repeatable reward table.
- Gym buildings are added inside existing town maps, so they work on current saves.

### H. Town requests (depends on A)
- Town boards post material delivery requests through the existing quest system: deliver N of a material for gold,
  skill XP and town reputation.

### I. Homestead (last; depends on E)
- A player-owned plot with buildings made from bulk logs and stone: stations, a storage chest, the vault, a trophy
  room for badges. RuneScape's Construction, as the main long-term material sink.

## Suggested order

1. A (materials core) alone.
2. B, C, E and G in parallel. C touches world generation only; G touches town maps and duel setup.
3. D and F after their dependencies.
4. H, then I.

## Art

Every material, tool, potion and gear piece needs an icon. Start with recolored existing sprites from
`sprites/items.atlas`. Free CC0 pixel-art packs are license-safe for later replacement. Do not block a package on art.
