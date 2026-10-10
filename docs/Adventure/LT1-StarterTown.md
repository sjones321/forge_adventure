# LT1 — Havenbrook (Starter Town) placement checklist

Hand-made town map: `forge-gui/res/adventure/common/maps/map/ascendant/starter_town.tmx`

**Do not let agents edit this TMX.** Steve owns it in Tiled. Wiring lives outside the map
(POI, biome list, shops, townsfolk/dialog JSON, object templates).

## Overworld placement (already wired)

| Item | Value |
|---|---|
| POI `name` | `StarterTown` |
| Display name | Havenbrook |
| Type | `town` |
| Map | `../common/maps/map/ascendant/starter_town.tmx` |
| Biome | Colorless (`world/biomes/colorless.json`) |
| Pin | `radiusFactor` 0.01, `offsetX` 0.06, `offsetY` 0.0 |
| Why here | ~50 tiles east of the player start / Spawn at map center (500,500) on the 1000×1000 home plane, outside `minTownSpacing` (40) |

New games only: world generation places the POI when the home plane is created.

## Objects already on the committed map

These are present today (positions may move as Steve edits art):

| Object | Template | Notes |
|---|---|---|
| Entry + exit | `../../obj/entry_up.tx` | Empty `teleport` = spawn from overworld **and** exit on collide (stock pattern). Near the south road (~tile 28–29 X, ~47–48 Y). |
| Inn | `../../obj/inn.tx` | Opens the inn UI. |
| Shop | `../../obj/shop.tx` | Rarity lists: common `White,Blue,Black,Red,Green`; uncommon `Artifact,Multicolor`; rare `Colorless`; mythic `Planeswalker`. |

If the **entry** object is missing while you edit, Ascendant logs an LT1 warning and spawns the player at a configurable fallback fraction of the map size (`lt1FallbackEntryXFraction` / `lt1FallbackEntryYFraction` in `config.json`). No crash.

## Objects for Steve to place in Tiled

Layer: **Objects** (objectgroup). Use Insert Template / insert from `forge-gui/res/adventure/common/maps/obj/`.

### 1. Townsfolk NPCs (required for LT1 NPCs)

Template: `townsfolk.tx`  
Type: `townsfolk`  
Property: `townsfolkId` (string) — must match `world/townsfolk.json` `id`.

| townsfolkId | Suggested position | Relative to |
|---|---|---|
| `havenbrook_mira` | Near the inn interactable | Just south or east of the inn object (id 2), on walkable ground |
| `havenbrook_bren` | Near the shop | Just south of the shop object (id 3), on walkable ground |
| `havenbrook_sela` | Town square / open plaza | Center of the largest open paved area on the Ground layer |

Dialog and sprites are **not** pasted into the TMX. They load from:

- `Shandalar Ascendant/world/townsfolk.json`
- `Shandalar Ascendant/world/dialogs/starter_town/*.json`

Missing / unknown `townsfolkId`: LT1 warning, NPC skipped, no crash.

### 2. General store shop (recommended)

Template: `shop.tx`  
Type: `shop`

| Property | Suggested value |
|---|---|
| `commonShopList` | `GeneralStore` |
| `uncommonShopList` | `Artifact,Equip` (optional) |
| `rareShopList` | *(empty or omit)* |
| `mythicShopList` | *(empty or omit)* |
| `hasSign` | `true` (template default) |
| `signXOffset` / `signYOffset` | template defaults |

Place on a storefront facing a walkable path (a second shop next to Bren, or replace the existing shop’s common list with `GeneralStore,White,Blue,Black,Red,Green` if you prefer one shop).

`GeneralStore` is defined in Ascendant `world/shops.json` (commons by color + starter tools; CS0 source printings apply).

### 3. Optional stock-town extras (nice to have)

| Template | Type | Suggested spot |
|---|---|---|
| `spellsmith.tx` | `spellsmith` | Near craft / market row |
| `quest.tx` | `quest` | Town board / notice wall; set `questtype` if used |
| `exit.tx` | `exit` | Only if you want a separate exit from the entry (usually unnecessary — entry with empty teleport exits) |

### 4. Optional dialog-only markers

Template: `dialog.tx`  
You may set either:

- `dialogFile` = `world/dialogs/starter_town/sela.json` (preferred for LT1), or
- inline `dialog` JSON (stock style).

Prefer `townsfolk.tx` + `townsfolkId` for named NPCs so portraits/sprites come from townsfolk data.

## Tunables (Ascendant only)

In `ConfigData` and `Shandalar Ascendant/config.json` (LT1 block):

- `lt1LivingTowns`
- `lt1WarnMissingMapObjects`
- `lt1FallbackEntryXFraction` / `lt1FallbackEntryYFraction`
- `lt1StarterTownPoiName`

Stock worlds unchanged. No shipments, roads, or town-to-town messaging in this package.

## Co-op

Not applicable for LT1 v1: townsfolk and shops are local map data on the host’s world; no co-op sync is introduced here (see CO2 location invites later).

## In-game smoke steps

1. New Game on **Shandalar Ascendant**.
2. From Spawn, walk ~east (~50 tiles) until the **Havenbrook** / WasteTown marker appears.
3. Enter: you should spawn at the entry (or fallback if entry was deleted mid-edit).
4. Use the inn and the shop; shop stock uses CS0 printings.
5. After placing townsfolk objects in Tiled, talk to Mira / Bren / Sela.
