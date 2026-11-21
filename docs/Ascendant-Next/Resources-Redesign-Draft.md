# Resources redesign (DRAFT, 2026-10-13)

*Proposal marked up with Steve's answers the same day. Replaces the Forge Adventure "nodes pop up near you"
model (scrap and trees spawning around the player) with resources that live in the world.*

*Fits Ascendant-Next: read `Architecture-Brief.md` and `WG1-Spec-Stage1.md` first. Surface gathering is
Architecture stage 3; underground digging is stage 5 (WG2). Not in WG1 stages 1–2.*

## The big change: resources belong to places

- Nothing spawns around the player. Resources are **part of the plane**, generated with it, and stay put until you
  take them (then regrow or are gone, see below). Where you go decides what you find.
- **Surface** = what grows and lies on the ground. **Underground** = what's buried (most mining). **Water** = what
  lives in it (including fishing). **Sky** = what's up there (later).
- Taking something writes a **chunk delta** (tree → stump, tile dug, plant cleared). Same save model as the brief:
  generate chunk, then apply deltas. Regrowth is a seasons-calendar event, not a respawn near the player.
- **Partner co-op:** the partner lives in the host's world (same plane saves); they can build their own base or live
  in yours. Resources taken are gone for both (shared chunk deltas).

## Surface (gather by hand or with a tool) — stage 3

WG1 already places **tree** and **rock** terrain (and landmark trees). Stage 3 makes those interactive: an axe on a
tree, a chisel on a boulder. Plants and scrap get their own placed markers in the chunk, not floating nodes.

| Resource | Where | Skill / tool | After taking |
|---|---|---|---|
| Logs (oak, willow, yew, ironwood; jungle hardwood / dead wood later) | every tree tile / tree sprite, by biome | Woodcutting / axe tier | stump; **regrows over a full season** (SN1 calendar) |
| Plants (wild herbs → worldtree bark; swamp remains; fibres, mushrooms) | meadows, forest floor, swamp, jungle | Foraging / sickle | regrows (seasonal) |
| Surface stone and boulders (limestone → starstone) | rock clumps, mountain slopes | Quarrying / chisel | some boulders regrow slowly; ridges don't |
| **Some ore outcrops** | rare mountain / ridge showings | Mining / pickaxe | gone; tip you that a vein is below — **most ore stays underground** |
| Clay and sand | riverbanks, beaches | Foraging / shovel | regrows |
| Scrap (scrap → power cell) | ruins, waste POIs, Kaladesh-style planes — **not** random overworld spam | Salvaging / toolkit | gone until that ruin is restocked (rare) |
| Waters / brine / pearls (surface edge cases) | springs, ice, coast beds you can reach without diving | Delving / vial or net | regrows or seasonal |
| Feathers, hide, bone | from creatures you beat or hunt | (drop) | n/a |
| Crops | your own farm plots (fortress) | Farming | replant |

**Stage 3 done when:** Steve can walk to a forest, chop a real tree (chunk delta + log in inventory), quarry a rock,
pick a plant, fish a river, open inventory, and use a first crafting station — no nodes materializing next to him.

## Underground (most mining) — stage 5 / WG2

- The underground layer is **mostly solid dirt and stone** aligned 1:1 with the surface. You dig through it with a
  pickaxe; each tile dug gives a little stone or dirt.
- **Ore and gems are buried veins and pockets** inside it, not floating nodes: you find them by digging toward them.
  **Some surface outcrops** exist as showings; the bulk of ore is below. What's under you depends on the biome above:

| Above | Common below | Rarer below | Maps toward today's ids |
|---|---|---|---|
| Meadow / plains (white) | iron, coal, clay, limestone | silver, quartz | `ore_iron`, `limestone`, `quartz` |
| Islands / coast (blue) | salt, coral stone, pearl beds (under water edges) | sapphire, azure crystal | brine/pearl line, `sapphire`, `azure` |
| Swamp (black) | peat, bog iron, bone deposits | onyx, grave-salt, ancient bones | `dead` line, `onyx` |
| Mountains / volcanoes (red) | iron, copper, sulphur/brimstone, obsidian near volcanoes | mithral → rune, ruby, garnet, dragonfire ash | `ore_*`, `ash`/`brimstone`/`dragonfire_ash`, gems |
| Forest / jungle (green) | amber, root-wood, clay | emerald, jade, living crystal | `emerald`, plant/crystal edge cases |
| Wastes / ruins | scrap pockets, thran bits | rare power cells | `scrap` line |

- **Depth matters** (when more layers exist): deeper = rarer, harder stone that needs a better pickaxe, more danger.
- **Hard stops:** no digging under deep ocean or rivers; bedrock veins block until a top-tier pick.
- **Finding veins:** a Mining skill perk or a "dowsing" item hints at nearby veins (sparkles in the wall), so it's
  digging with purpose, not random tunnelling. Surface outcrops are another hint.
- **Caves**: natural open caverns underground with exposed veins, creatures and loot; dungeons connect to them.

## Water (in from stage 3)

- **Fishing is in** ("what good is a game without fishing"): rod in rivers, lakes and sea, by biome — fish, crabs,
  eels, rare catches; pearls from coast beds.
- Sea encounter zones give water creatures (fights) and their drops.

## Sky (later)

- Feathers and aether materials from sky creatures; "skystone" from floating rocks on some planes (Zendikar!).

## Regrowth and scarcity

- **Trees regrow over a full season** on the SN1 calendar, not a few days. Plants follow the same seasonal idea;
  boulders slowly; **ore veins never refill** (you dig further / deeper instead; surface outcrops stay gone).
- Each **set plane** has its own resource table in its `PlaneProfile` (Innistrad: wolfsbane, silver, blessed water;
  Kaladesh: aether, quicksilver; Zendikar: hedron shards, skystone). That is a big reason to planeswalk.
- Depletion is on the **shared host world** in partner co-op (same plane saves / chunk deltas).

## What happens to today's materials

Keep **ids, recipes, skills, refine-to-dust, and icons**. Change **where they come from**.

| Family (materials.json) | Today (Adventure) | Next (this draft) |
|---|---|---|
| `logs` | tree nodes spawn near player | WG1 tree tiles / sprites, by biome tier |
| `plants`, `dead` | plant / remains nodes | placed ground cover in forest/swamp/jungle |
| `sacred_stone` | stone nodes on plains | surface boulders / quarry rock |
| `ore`, `ash`, gems | vein/vent nodes on mountains | mostly underground veins (stage 5); rare surface outcrops; ash near volcanic underground |
| `waters`, `brine`, `pearls`, `crystal` | Delving nodes / drops | coast, springs, caves, under-island beds; fishing |
| `scrap` | wastes nodes near player | ruins and set-plane factories only |
| `feathers`, `hide` | enemy / color drops | still creature drops (+ sky later) |
| `prismatic`, `boss`, `gym` | craft / bosses / gyms | unchanged sources |

Color-reagent rules from the current Ascendant roadmap (dust + mana reagents for card craft) stay; only the gather
loop changes.

## Build order (Reed)

1. **After WG1 walk + fight:** surface gather on existing tree/rock placement + plant markers + fishing; inventory;
   one station. Rare ore outcrops allowed on the surface.
2. **With seasons port:** full-season tree (and plant) regrowth on the SN1 calendar.
3. **With WG2 layers:** dig, veins, caves, dowsing; most ore below.
4. **With set planes:** per-profile resource tables and unique materials.
5. **Partner co-op:** shared plane deltas so taken resources are gone for both.

## Steve's answers (2026-10-13) — locked in above

1. **Some outcrops on the surface**, but most ore is underground.
2. **Trees regrow over a full season** (SN1 calendar), not a few days.
3. **Fishing is in** ("what good is a game without fishing").
4. **Co-op is partner co-op:** the partner lives in the host's world (same plane saves); they can build their own base
   or live in yours. Resources taken are gone for both.
