# Shandalar Ascendant, next generation: architecture brief

*Steve + Wren, 2026-10-13. For Reed (Cursor) and anyone building it. Design source of truth; change it here first.*

## Decision

We stop renovating Forge Adventure. We build **our own game** on top of Forge's rules engine.

- **Keep from Forge:** the rules engine (`forge-game`), the ~30,000 scripted cards and card database (`forge-core`,
  `forge-gui/res/cardsfolder`, editions), the AI (`forge-ai`, plus our LLM opponent), and the machinery that runs a
  duel (`HostedMatch`, `RegisteredPlayer`, `GameRules`).
- **Ours, new:** the world, movement, layers, gathering, building, travel, saves, UI, and our systems (seasons,
  life-cycle economy, skills, collecting, fortress). Forge Adventure (`forge.adventure.*`) contributes **concepts
  only**; code from it is copied in only where it's clean and ours (e.g. seasons calendar math, economy pricing,
  skill XP curves), never imported as a dependency.
- The current Shandalar Ascendant (Forge Adventure fork) **stays playable** until the new game overtakes it.

## Shape of the code

- **Same repo, same stack:** Java 17 + libGDX (the stack Slay the Spire uses). New package root `forge.ascendant`,
  in a new Maven module **`forge-ascendant`** that depends on `forge-core`, `forge-game`, `forge-ai` and `forge-gui`
  (shared GUI interfaces), plus `forge-gui-mobile` only for what the duel bridge needs in stage 1.
- **Its own entry:** a top-level mode in the Forge app shell (like Adventure is today) for stage 1, so we inherit app
  start-up, settings, asset loading and the match screen. It owns its own scenes and never calls into
  `forge.adventure.*`. A separate launcher can come later if the shell gets in the way.
- **No static singletons for game state.** One `AscendantSession` object holds the current save, plane and player;
  systems get it passed in. (Forge Adventure's `Current.player()` / `MapStage.getInstance()` statics are what made
  it hard to change; we don't repeat that.)

## Core concepts

### Planes (the spine of the story and progression)
- A **plane** is one generated world. The **home plane** is Shandalar (Havenbrook at the centre). **Set planes** are
  generated when their set enters Standard through seasons (SN1), and you **planeswalk** between them through gates.
- **PlaneGenerator** turns `(seed, PlaneProfile)` into a plane. A **PlaneProfile** is data (JSON), so a new set
  plane is mostly a new profile, not new code:
  - biome mix and layout (home: five colour wedges around a meadow core; a set plane: weighted by the set's colours),
  - landmark kinds and art (Innistrad: moors, gothic hamlets, graveyards; Zendikar: cliffs, hedrons, floating rock;
    Kaladesh: factories, skyways; Ixalan: jungle and coast),
  - enemy and encounter pools (from the set), music, travel-station types, resource tables (what trees/ores drop).
- The world generator we're designing now (WG1 surface, WG2 layers) **is** the PlaneGenerator. Its Python
  prototype (`A:\GameAssets\worldgen\`) is the reference: same algorithms, same knob names, same numbers.

### Hand-tailored set planes (Steve, 2026-10-13)
- Each set plane is **designed with Steve**, not just generated: a tuned PlaneProfile plus **hand-authored set
  pieces** (its towns, signature landmarks, story locations, music, palette) that really fit the set. The generator
  fills the wilderness between them and guarantees reachability; the hand-made pieces are placed into it. More work
  per plane, worth it: planes are the story.
- The home plane follows the same rule: generated wilderness, hand-made key places (Havenbrook first).

### Towns and interiors (Stardew model)
- Towns are **seamless** (part of the plane, walked into on the road), but **building interiors are instanced**: a
  door loads a small interior map (inn, shop, smithy, homes), like Stardew Valley. Otherwise towns are only scenery.
- Interiors are hand-made per building type and reused with variations; key buildings get unique interiors.

### Islands
- **Docks only where people are:** every docked island has a settlement (village or town) that justifies the dock,
  with a reason to go there (shops, quests, unique materials, a landmark). More towns on the islands.
- **Wild islands** (uninhabited) have no dock; you reach them later by your own boat, glider, flier or swimming mount,
  and they hold rarer finds for doing so.

### Island mix (Steve, 2026-10-13)
- **Most islands are wild:** dungeons, resources and monsters, no people.
- **A few inhabited islands** have a real town (the port town template).
- **A few small ones** have just a couple of houses and a dock.

### Getting around: earn your way out from the centre (Steve, 2026-10-13)
- You start in the **centre** (Havenbrook and the plains). Each outer biome has a **traversal unlock** that makes it
  easy to cross. You *can* foray in before you have it; it's just slow and painful.
  - **Plains:** a regular mount (faster travel on open ground).
  - **Islands / sea:** a basic boat first, upgraded through play (speed, range, rough water, carrying capacity).
  - **Swamp:** a skiff (bog and deep water become passable, faster than wading).
  - **Mountains:** a cliff-climbing mount, e.g. a mountain goat (jump up cliff ledges instead of finding the
    switchback paths).
  - **Forest:** you choose one of three mounts, each a different way through:
    - a **stag** that bounds over fallen logs and gaps;
    - a **panther** that leaps up into the **canopy**, the forest's upper layer (branches and treetop walkways),
      so you run around up there;
    - a **bear** that bulls straight through brush and thickets.
- Without the unlock, the biome is crossable on foot but slow: wading in swamps, long detours around cliffs, and the
  sea limited to dock-to-dock ferries.
- Travel stations still connect towns you've reached. The unlocks are about the **wilderness between them**: dungeons,
  resources and wild islands.
- The unlocks are earned (quests, crafting, skill levels) and upgradable; they are the main progression gate besides
  the cards themselves.

### Layers
- Each plane has a **surface** layer, an **underground** layer aligned 1:1 with the surface (more depths later), and
  a **sky** layer aligned above it (see roadmap WG2). Layers share coordinates; a tile is `(plane, layer, x, y)`.

### Chunks and deltas (needed for real scale and digging)
- Planes are big (target 2000x2000 tiles or more per layer). The world is split into **chunks** (64x64 tiles).
- A chunk is **generated deterministically** from `(plane seed, layer, chunk x, chunk y)` on demand, so nothing is
  stored until the player changes it.
- Player changes (a tree gathered, a tile dug, a structure built, a station activated) are stored as **chunk
  deltas**. Loading a chunk = generate it, then apply its delta. Saves stay small however big the world is.
- Rendering and collision work per chunk; only chunks near the camera are loaded (streaming).

### Saves
- One save = account data (collection, skills, seasons, economy, quests, fortress) + one folder per **visited
  plane** (seed, profile id, chunk deltas, discovered stations, plane-local state). Plain JSON plus compressed
  delta blobs; versioned from day one; never Java serialization of live objects.

### The duel bridge
- The world never talks to Forge's engine directly. A **DuelBridge** takes a `DuelRequest` (player deck, opponent
  deck or theme, life totals, rules/format, any start-of-game effects, which AI/LLM drives the opponent) and
  returns a `DuelResult` (winner, turns, life, cards seen; the world turns that into rewards).
- Inside, it builds `RegisteredPlayer`s and runs a `HostedMatch` exactly as Forge does.
- **Stage 1 uses Forge's existing match screen** so the loop works immediately. The new duel screen (the hand work
  Steve and Reed are doing) replaces it behind the same bridge later; the world code doesn't change.

### Travel stations ("dial-a-ride")
- Biome-flavoured stations: docks and boats (islands), stagecoaches (plains), caravans (mountains), elf guides
  (forests), swamp skiffs (swamp). You **discover and activate** a station by using it once.
- Using any station opens the **travel map**: all activated stations highlighted, search by name, quest targets
  marked; pick one, pay the fare (gold) and the trip costs in-game time on the seasons calendar.
- Later: gliders, fliers and a swimming mount for direct travel; planar gates use the same map for planes.

## Stages (each one is playable; build in this order)

*Steve's feel-order and layer/dungeon notes: `World-Playable-Path.md`. Hand town templates: `Towns.md`.*

1. **Walk a plane (see scale).** Port the WG1 generator to Java; generate the home plane at real scale with chunks;
   stamp **Havenbrook** and the **approved plains market town** (other biomes keep procedural footprints until
   their template is approved); Ninja Adventure tiles; walk with collision; minimap; save and reload.
2. **Fight.** Enemies roam the plane; touching one starts a duel through the DuelBridge (Forge's match screen);
   win gives a reward screen (gold, cards from the current set), lose sends you back. Starting deck from a sealed
   pool like today.
3. **Malleable gather and travel.** Trees and rocks yield (chunk deltas), inventory, a first crafting station;
   travel stations and the travel map. Resource placement: `Resources-Redesign-Draft.md` (surface here).
4. **Town life.** Doors open to **instanced interiors**; shops, inn, townsfolk in the seamless town. More biome
   templates as each is approved (`Towns.md`).
5. **Base building, then layers.** Place structures on the plane (deltas). Then ladders into underground/sky:
   **high vertical headroom** for big bases/automation; **prebuilt caverns and tunnels** (not only player digouts);
   digging connects into them. Dungeons are **instanced procgen maps** with a tileset per biome (see the path doc).
6. **Planes.** Planar gates and the first set plane from a PlaneProfile, tied to seasons.
7. **Port our systems** as each becomes needed: seasons, life-cycle economy, skills, collecting, fortress,
   achievements. (Some come earlier when a stage needs them, e.g. skills with gather.)

## Rules for building it

- **Visual things need an approved picture first** (Steve approves a mockup or prototype render before code), and
  feel-based things (hand, movement) are iterated live with Steve.
- Small slices Steve can play. No multi-PR epics without a playable step in between.
- Tests: generator determinism (same seed, same plane), reachability guarantees, save/load round trip, DuelBridge
  with a scripted duel. Keep the Linux runner (Ark CT 110, `forge-test`) green.
- Credits for every asset in `CREDITS.md`; CC0/permissive only.
