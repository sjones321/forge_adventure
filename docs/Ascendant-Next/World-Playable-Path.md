# World playable path (what Steve needs to see next)

*Steve, 2026-10-13. This is the build order for Ascendant-Next from here. The Architecture-Brief stages still
stand; this doc says what you need to **feel** in what order, and locks a few layer/dungeon decisions that were
still open. Resources: `Resources-Redesign-Draft.md`. Towns: `Towns.md`. Generator: `WG1-Spec-Stage1.md`.*

*Lucy: `Notes-for-Lucy.md` in this folder is the handoff. `Base-Hall-MTG-Automation-Design.md` (2026-10-16) is the newer lock where it conflicts with the old fortress brief.*

## What we have today

- **Havenbrook** (hand-made, in the repo) and an **approved plains market town** template (`tools/towns/plains/`,
  "perfect").
- **WG1 Python prototype** at real scale (2000×2000): coastline, biomes, rivers, volcanoes, procedural towns,
  Havenbrook stamp, reachability — renders only, not a walkable game yet.
- **No `forge-ascendant` walk loop yet.** Seeing scale in-game is the next real deliverable.

## Feel order (playable slices)

### 1. Scale — an actual world you can walk

**Done when:** you launch Ascendant-Next, stand in Havenbrook, walk out into wilderness, find the **plains market
town** on a road (stamped template, not a placeholder footprint), keep walking across biomes toward the coast,
open the map, save, reload. Same seed → same world.

- Plane size: WG1 defaults (2000×2000, 16px tiles, chunks 64).
- Hand towns in the skeleton: Havenbrook at centre; **at least one** approved biome template (plains) placed as a
  real white-biome town. Other biomes keep procedural footprints until their template is approved.
- Procedural wilderness + roads + stations between them so the map doesn't feel empty.
- Collision, Y-sorted sprites, streaming chunks — enough to judge "does this feel big / empty / cramped?"

No dig, no build, no interiors required for this slice. Doors can be solid. Enemies optional (stage 2 of the brief).

### 2. Malleable terrain — the world yields

**Done when:** you chop a tree (stump + chunk delta), dig a surface hole or quarry a rock, reload, and the change is
still there. Undo/refill only via the rules in the resources draft (seasonal tree regrowth, etc.).

- Chunk deltas are the save unit (Architecture-Brief).
- This is the proof that the world is editable before base-building UI exists.
- Ties to surface gather in `Resources-Redesign-Draft.md`; fishing can land in the same slice or right after.

### 3. Base building — claim ground and put walls up

**Done when:** you claim a site (or just build in the wild), place floors/walls/stations from materials, walk the
footprint, save/reload. Partner co-op later shares the same deltas.

- Building pieces are chunk deltas on the plane (and on underground/sky layers when those exist).
- **Building interiors** are **instanced maps** (door → small interior TMX), same model as town interiors — not a
  second fake overworld. Fortress "inside" rooms use that pattern when you want detailed indoors.
- Automation and big bases come after the place-and-persist loop feels good.

### 4. Layers up and down — depth for bases, caves, and danger

**Done when:** ladders/hatches move you between aligned layers; you can dig a shaft; you find a **pre-carved cave**
that wasn't just your digout; you can start stacking a multi-level base.

#### Vertical range (Steve's lean, 2026-10-13)

- Tempted by a **very high** number of levels up and down so crazy bases and automation have room.
- **Working proposal for the brief (still tunable):** layer index is a signed int; soft comfort band for "normal
  play" (e.g. a handful of sky platforms and a few deep mine strata), with a **high hard cap** (order of tens to
  low hundreds per direction) so saves and streaming stay sane. Exact numbers get locked when malleability ships
  and we measure chunk-delta size / load cost — not before you feel a 3–5 level base.
- Sky and underground share XY with the surface (Architecture-Brief / WG2).

#### Not just one big digout

- Generator (and later enemy activity) places **prebuilt caverns, tunnels, and delves** — pockets, worm-paths,
  dungeon mouths — so the underground is a place you discover, not only a quarry you excavate.
- Player dig connects into those voids; veins show in walls; enemies can own sections.

#### Where you build (Steve, 2026-10-18)

Core Keeper is the reference for **down**, not for the home-plane start. Core Keeper begins in solid earth: every wall is diggable, some cells are minerals, some open into caverns, some are already finished rooms. You dig out a hole and that hole is the base.

The home plane still **starts on the surface** (the walk slice). What you clear up there is mostly **trees**. A **ladder you place** is how you change layer, up or down. Layers share XY with the surface.

| Place | What it is for |
|---|---|
| Plains | Wide open. Build on the surface. Food is plentiful here. |
| Heavy trees | Chop on the ground. **Up** is already a canopy walkway. |
| Up, everywhere else | Not diggable sky. Platforms you build out from the ladder. |
| Down (land) | Core Keeper dig. Minerals in the wall, caverns, some finished rooms. The base is the hole you dig. |
| Mountains | More caves. Down should meet those, not only dirt you dug yourself. |
| Islands | Down is water you can build in. |
| Swamp | Don't count on a flat surface base. Build higher, or deal with the swamp some other way (still open). |

### 5. Instanced maps — interiors, dungeons, (later) special sites

Already decided for towns: seamless outside, **instanced interiors**. Same pattern:

| Kind | How it loads | Notes |
|---|---|---|
| Town / fortress interiors | door → small TMX | Hand templates + variants |
| **Dungeons** | entrance → instanced map(s) | **Procedurally generated**; not hand-authored every time |
| Set planes | planar gate → full plane | Hand-tailored profile + set pieces (Architecture-Brief) |

**Dungeon tilesets:** a small set, **one flavour per biome** (plains/crypt-clean, island/tide, swamp/bone,
mountain/ore, forest/root, wastes/ruin) so delves match the land above. Expand only when a set plane needs a
unique look.

Dungeons can be multi-level internally; that is separate from the open-world layer stack, but entrances should
meet caves/tunnels on the underground layer where it makes sense.

### 6. Set planes

As in the Architecture-Brief: seasons unlock set planes; each is a tuned PlaneProfile plus hand set pieces;
wilderness fills between. Resource tables per plane (resources draft). Build after the home plane feels right at
scale.

## Build order for Reed (code)

1. **`forge-ascendant` walk loop** — port WG1 skeleton + chunk gen enough to walk; stamp Havenbrook + plains
   market town; map; save. *(This is the "actual world" slice.)*
2. **Malleable deltas** — chop/dig/quarry persist.
3. **Base build** — place structures; doors to stub interiors OK.
4. **Layers** — underground/sky with high headroom; prebuilt caverns/tunnels; dig into them.
5. **Procgen dungeons** — biome tilesets; entrance from world/caves.
6. **Set plane #1** — when home plane scale + dig + build have Steve's nod.

Fight/DuelBridge, travel stations, and systems port in when a slice needs them (don't block scale on them).

## Open / tunable

- Exact **hard cap** on layers up/down (Steve wants it high; measure before locking).
- How many **hand town templates** must be stamped before scale feels fair (minimum: Havenbrook + plains).
- Whether the first walkable build uses Forge's shell + Ninja tiles only (yes for speed), or waits on custom art.
