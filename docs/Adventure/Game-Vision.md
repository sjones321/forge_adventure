# Game Vision — a co-op Magic adventure built on Forge

Working title: **TBD** (must not include "Magic" or Wizards of the Coast branding).
Setting: **the Eternal Engine**, Steve's original cosmology (`Lore.md`).

## The pitch

A cozy, systems-heavy adventure in the spirit of Stardew Valley and RuneScape, where **Magic: The Gathering is the
combat**. You gather, craft, build a home base and explore an endless chain of planes. Every fight is a real game of
Magic, played by Forge's rules engine.

## What Forge does, and what we build

| Forge provides (do not rebuild) | We build |
|---|---|
| Card database and card images (downloaded, never bundled) | Overworld, home base, planes, delves |
| Full rules engine (`forge-game`) | Systems: skills, gathering, crafting, economy, gyms, prestige |
| AI opponents (`forge-ai`) | Content: maps, towns, NPCs, quests, art |
| Network duels | Co-op overworld, controller-first UI |

Stay on **libGDX / Java**: same language as the engine (no bridge), and one codebase for Windows, Mac, Linux and
**Android**. Everything built in the Shandalar Ascendant packages carries over.

## Design pillars

1. **Systems first.** Gathering, crafting, skills and progression are the heart of the game; every system feeds
   another (gather → refine → craft → fight → rarer materials).
2. **A rolling adventure, not resets.** The world keeps growing. The only resets are **Standard rotation**
   (cards move to Historic, nothing is lost) and **prestige** (opt-in, rewarded).
3. **Respect the grind.** No dailies that punish absence, no paywalls, nothing expires.
4. **Controller-friendly everywhere**, including duels (the biggest UI job).
5. **Co-op** with a friend over LAN/Tailscale, no servers.
6. **Free and open (GPL-3.0).** Code stays open; art may carry its own license.

## World structure: the Multiverse

### Fortresses (the heart of the game)
Tone: **war-torn frontier, not a cozy farm.** You carve out strongholds on a contested map and hold them.
- **Claim a site**: walk to an open spot on the world map (away from towns) and plant a banner. The site becomes a
  fortress marker; entering it loads its own **instanced map**, like a town or dungeon.
- **Build inside**: place structures on a grid (walls, gates, towers, stations, storage, barracks, war room,
  shrine, training yard, vault) using gathered materials. A **Construction** skill (1-99) gates what you can build.
- **Grow it**: Camp → Palisade → Keep → Castle → Citadel. Each tier opens more build space and structure tiers.
- **Defend it**: warbands **raid** your fortresses. Defenses become duel advantages (walls = extra life, towers =
  starting creatures or damage, gates = fewer waves). Big sieges are Archenemy fights. A lost raid damages
  structures and takes part of the stored goods, never everything.
- **Territory**: a fortress controls the land around it: more resource nodes, weaker roaming enemies, outposts
  linked to it, and a fast-travel anchor. On other planes, a fortress is your portal anchor.
- **Garrison**: recruited companions defend raids alongside you.
- Your **first fortress is your prime base** and never resets except by prestige. Only it has the vault, trophy
  hall, Hall of Remembrance and top-tier stations. Each set plane allows **one forward fortress** (portal anchor,
  smaller and tier-capped); outposts are lighter links.

### Logistics, not conveyors
- **Auto-sorting storage**: every fortress chest feeds one sorted stockpile; stations pull from it directly.
- **Workers** staff buildings (haulers, quartermasters, smiths) to run simple automation: refine raw materials,
  restock stations, craft standing orders.
- **Supply lines** replace conveyor belts: caravans run between your fortress, your outposts and the towns you have
  allied with or conquered, including towns on far planes. A supply line from a far plane stocks your fortress
  shop with that plane's cards.
- **Towns**: ally with a town through trade and quests, or conquer it. Allied towns trade; conquered towns pay
  tribute but resist and can rebel.
- **Outpost network**: linked outposts trade with each other and report intelligence (raid warnings, invasion
  movement, rare nodes, delve sightings).

### The Nothing (story and stakes)
Set in Steve's own cosmology, **the Eternal Engine** (see `Lore.md`). You are a **Death Watch Initiate** sent into
the Magic multiverse to find the source of **the Nothing**, which literally eats the map: land, towns and roads
become nothingness. Its soulless minions are empowered by a hidden **Fallen**. After you master your first
Standard set, the Nothing starts competing with you for the multiverse. You investigate plane after plane, close
breaches, defeat commanders and finally expose the Fallen's operation, making it too messy to hide from the other
Firstborn. Losses are real; dialog stays skippable; the stakes live in the world.

### Trading between players
- Trade cards, materials and items with your co-op partner face to face (trade window, both confirm).
- Later: trade by code or file between players who aren't connected.

### Set planes
- **Each set is a plane**: its own instanced map, like a large dungeon. Its enemies, rewards and shops draw from that
  set; its biomes and town names follow the set's theme. A plane is generated from a template plus the set's data
  (the "plane-per-set generator").
- **Planar portals** connect planes. Travel is seamless: walk into a portal, load the plane, walk back out.
- **Ties into Standard rotation**: planes of the sets in your Standard window are **in alignment** and reachable.
  Mastering a set unlocks the next plane. When a set rotates out, its plane **drifts out of alignment**: still
  reachable through a costlier portal, and its cards now count as Historic.
- Only the current plane is loaded; the others are saved to disk, so many planes stay cheap.

### Delves (pocket planes)
- **Pocket planes spawn at random** inside set planes as temporary portals. Enter, go deeper floor by floor,
  come back out. They hold the rarest nodes (tier 4 materials, gems), guardians and relics.
- **Depth** is the score: deeper floors are harder and pay more. Delves absorb the earlier "Rifts" idea
  (roguelite runs) as one delve type alongside resource delves.

## Progression

- **Skills 1-99** with talent trees (built).
- **Collection** grows through rewards, crafting (dust + color reagents) and set mastery (built).
- **Formats per plane**: Bellwarden Standard, Historic, Pauper or Commander (with 2HG for co-op). You pick the
  home plane's format at the start and each new plane's format when you open it. Enemies, gyms and events on a
  plane play its format. Gyms and the League are built; tournaments and Grand Prix are planned.
- **Achievements and card styles**: account-wide achievements, set completion rewards (and something unique for
  completing every set), and unlockable alternate arts and foils earned from packs, quests and achievements.
  Both survive prestige.
- **Prestige** is the game's New Game+: an opt-in full reset (collection, gold, materials, skills, planes) that keeps unlocked staples,
  the Hall of Fame, achievements, card styles, cosmetics and the prestige tree. **Prestige XP scales with how far you went**: delve depth
  reached, planes mastered, League titles, total level. Spend it on the account-wide prestige talent tree.

## Co-op: shared world (decided)

Both players are in the **same world at the same time**. The host's save owns the world; each player keeps their
own character (collection, decks, skills, materials, items). The host is authoritative over the world (enemies,
nodes, POI changes, loot rolls). Players **team up or go their separate ways**: party up by invite, leave any time,
and nobody is ever pulled into a fight or a location without saying yes. Co-op fights are two humans on one team
against the enemy, using Forge's existing network duel code. Connection is direct over LAN or Tailscale, no servers. See roadmap packages CO1-CO4.

## Milestones

1. **Co-op foundation**: session and connection (CO1), shared overworld (CO2), co-op duels (CO3).
2. **Own identity**: name, launcher, app, separate from stock Forge. **Working title: Bellwarden: Planes of
   Nothing** (chosen 2026-10-08). Avoid "NeoForge" (Minecraft mod loader), "___ of Eternity" (Pillars of
   Eternity), and "Death Watch"/"Deathwatch" in the title (Star Wars / Warhammer 40K); the canon name is fine
   inside the game.
3. **Controller-first** outside duels.
4. **Multiverse core**: multi-plane save, home plane, planar portals, first generated set plane.
5. **The first living town**: one hand-made town as the art test, built lived-in from day one (see "Living
   towns" below). Townsfolk, friendships and the first Archive entries are built here, along with the first
   **Hall of Remembrance** room and alcove: the Archive, relationship screen and fast travel in one place (see
   `Lore.md`).
6. **Seasons**: the calendar, tied to Standard rotation (each Standard window is a season).
7. **The Nothing**: map erasure, breaches, transit nodes, Watcher warnings, Harmonic Bell placement. Needs 4-6
   first: it has nothing to threaten until there's a town you know and a calendar to race.
8. **Delves** with depth and prestige XP.
9. **Co-op systems** (CO4): shared home base, trading, co-op gyms and delves.
10. **Controller support in duels.**
11. **Farming** at the home base.

### Living towns

Towns are not shop lists. They are lived in, and **every non-shop building is a piece of the Archive**: culture
lives in the places that aren't selling you anything. (See the core loop in `Lore.md`.)

- **Homes**: where you learn who people are. Family keepsakes, kids' drawings, a grandmother's recipe. Being
  invited into someone's home is a friendship milestone, not just a door.
- **Church / temple**: what the town believes and how it grieves. Services, weddings, funerals. A culture's
  beliefs about the Engine and the Between are top-tier Archive entries.
- **Town hall**: how the town governs itself. Disputes, elections, the mayor's problems. When the Nothing
  approaches, this is where it becomes politics.
- **Tavern, school, graveyard** and others each record something shops never will.
- **Each set plane gets its own civic identity**, drawn from the plane's culture (temples to the gods on a
  Theros-style plane, guildhalls where a town hall would be on a Ravnica-style plane).
- **Every townsperson has a deck that says who they are.** Casual games (at the tavern, on the porch) are the heart
  of the friendship system: you learn about people by playing them, and they open up the more you play. Gifts and
  conversation still matter. Examples: the smith's sturdy artifact deck she built herself; the priest's white
  lifegain and protection; the school kid's janky rubber-banded pile he's very proud of.
- **Top friendship reward: their signature card.** At the highest friendship, a townsperson gives you the one card
  that is most *them* (the artifact the smith forged into her deck years ago; the kid's favorite beat-up creature).
  It joins your collection, and a copy sits in their alcove in the Hall of Remembrance. If their town is lost, the
  card is one of the last pieces of them left: playing it carries them with you.
- **Learn Magic from the townsfolk (optional tutorial).** Each townsperson teaches the part of Magic their deck is
  about: the school kid teaches the basics (lands, mana, creatures, attacking), the priest teaches lifegain,
  protection and instants, the smith teaches artifacts and equipment. Learning Magic and making first friends are
  the same activity. Lore reason: this bubble's magic runs on local rules, and comprehension is the multiplier.
  Experienced players skip it: tell the kid "I know how to play" and he challenges you to a real game. Forge's
  built-in puzzle mode could power the guided lessons.
- **Shops restock by shipment.** Stock arrives from somewhere else (another town, a trade route), not by magic
  refresh. (Idea: shipments travel the roads, so when the Nothing cuts a road, the towns past it start running
  short. Ties into Logistics.)
- **Travel and communication system: Tiny is building it.** It will need a research system set up to support it,
  and a lore pass to fit the Eternal Engine. Coordinate with Tiny before building anything that overlaps
  (shipments, roads, messaging between towns).
- **Every town has a general store**: some equipment, and if you're lucky, a little dust or crafting materials.
- **Scope**: interiors and routines are a lot of content per town. Prove it on the hand-made town first, then
  build generated towns from per-plane templates.

## Legal guardrails

- Never charge money or put anything behind a paywall.
- No Wizards logos or "Magic" in the name or branding.
- Card images are downloaded at runtime, never shipped.
- Original art for the world, characters and towns is ours.
