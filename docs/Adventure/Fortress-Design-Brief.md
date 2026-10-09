# Fortress design brief (for Vex)

Written by Wren on 2026-10-09 so you can design the fortress without reading the whole repo. Everything here is a
summary; the source docs are `Game-Vision.md`, `Lore.md` and `Ascendant-Roadmap.md` (sections FT1-FT4, MV3, LG1-3,
CO4, CO5, WAR1). Where this brief and those files disagree, tell Steve and Wren.

## The game in one paragraph

**Bellwarden: Planes of Nothing** (working title) is a co-op, Stardew/RuneScape-style RPG built on Forge, where every
fight is a real game of Magic. You gather, craft, build and explore an endless chain of planes, one per Magic set.
Setting: Steve's own cosmology, the Eternal Engine. You are a Death Watch Initiate investigating **the Nothing**, which
permanently erases land and towns. Tone: war-torn frontier, not a cozy farm, but with real warmth (friendship with
townsfolk is the core loop).

## What the fortress already is (built, playable now: FT1)

- **Claim a site:** plant a Banner item on open walkable land at least N tiles from towns and other points of
  interest. It becomes a fortress marker on the overworld.
- **Instanced map:** entering loads its own map, a template camp (`fortress_camp.tmx`) with a buildable zone and an
  entry point. Player structures are saved separately and placed on load.
- **Build mode:** grid cursor (keyboard, mouse, controller), structure picker, green/red footprint preview, rotate,
  demolish with partial refund. Paths are kept walkable (the game refuses placements that would wall you in).
- **Structures are data** (`world/structures_fortress.json`): id, footprint, sprite, material cost, Construction
  level, tier, effects, station type. Costs use gathered materials (logs, ore, stone and so on).
- **Construction skill** (1-99, with a talent tree) gates what you can build.
- **Stations** built here are the same as in towns: Forge, Workshop, Apothecary, Jeweler, Spell Smith. They're
  openable and currently use stock Forge building art.

## Decided, not built yet

- **Tiers (FT2):** Camp → Palisade → Keep → Castle → Citadel. Each tier costs bulk materials and Construction levels,
  grows the buildable zone and unlocks structure tiers. First structure list: wooden/stone walls and gates,
  watchtower, barracks, storage, war room (quests/contracts), training yard (Dueling XP), library (deck tools), shrine
  (blessings), vault, trophy hall.
- **Raids and sieges (FT3):** warbands raid on an in-game timer, announced in advance, only while the player is online.
  A raid is a series of duels; defenses change the duels (walls = extra starting life, towers = starting creatures
  or damage, gates = fewer waves, barracks garrison = an AI ally). Big sieges use the Archenemy format. Losing damages
  structures (repairable) and takes a capped share of stored goods, **never everything**.
- **Territory (FT4):** a control radius on the overworld with more gathering nodes, weaker roaming enemies, linked
  outposts and a fast-travel anchor. On other planes a fortress is the portal anchor.
- **Limits:** one **prime base** per player (the first fortress, on the home plane). Only it can build the vault,
  trophy hall, Hall of Remembrance and top-tier stations. Each set plane allows **one forward fortress**: portal
  anchor, fast travel, supply hub, smaller structure list, tier cap (start at Keep). Outposts are lighter links.
- **Logistics (LG1-3), not conveyor belts:** auto-sorting storage (every chest feeds one stockpile that stations pull
  from), workers (haulers, quartermasters, smiths) running simple automation, and supply lines (caravans between
  fortress, outposts and allied or conquered towns).

## Lore you must respect

- **Fortresses anchor reality.** The Nothing can only **besiege** a fortress, never erase it. The land around it can
  still be eaten. A fortress is the one thing on a plane that can't be lost.
- **The Archive is a substation in your fortress**, talking to the Archivist (the Death Watch's mega-AI). Your
  friendships with townsfolk are recorded there, and what you archive survives even when the Nothing eats a town.
- **The Hall of Remembrance** is reached from your fortress: a pocket-dimension hall, one door per plane, one alcove per
  town, filling with keepsakes as you make friends. It doubles as fast travel and the relationship screen. Lost towns
  leave walkable "echoes".
- **Harmonic Bells** (where the contingent anchors protection against the Nothing) are argued for with Archive
  knowledge. The fortress is where that case gets made.
- The **Watcher** (your guide, she) and the **Supervisor** (gruff, a Death Watch Knight with a lost world) are
  characters who could plausibly have a presence at the fortress.

## Co-op constraints (decided today)

- **CO5 partner model (like Stardew farmhands):** the guest has a character that lives in the **host's** world save.
  Solo characters never enter co-op. Design question for you: does a partner get their own space or buildings inside
  the host's fortress (a cabin, a room), and can they build? Stardew gives farmhands a cabin.
- Lore says each player has their own Hall of Remembrance (a guest may visit the other's, look only).
- Trading will be a simple host-side trade once CO5 lands.

## Art and production constraints

- **16px tiles**, Kenney Tiny Town/Battle/Dungeon tilesets (CC0) plus Steve's own tiles, in Tiled maps.
- **Steve has aphantasia.** He can't draw from a blank canvas; he molds drafts. Anything you design that needs new art
  should come with a clear reference: describe it concretely, or point at existing tiles to recolor or adapt.
- **Ninja Adventure pack** (CC0, downloaded, not used yet): animated characters, animals, monsters, items and effects.
  Good for workers, garrison and townsfolk who move in.
- Stations and structures currently use stock Forge building sprites (`buildings.atlas`).
- The code is Java/libGDX. Anything you design must be buildable by Grok (the coding agents) as data plus systems, so
  please think in terms of structure lists, numbers that can be tuned, and screens.

## The open questions (what Steve wants to design with you)

1. **Feel:** war camp, cozy home, or both? (Steve's earlier direction: "war-torn frontier, not a cozy farm".) Where do
   warmth and personality come from?
2. **Decoration:** free placement of decor (Stardew-style), or only functional structures? Do decorations do anything?
3. **Interiors:** can you enter buildings (your quarters, the war room, the library)? How many interiors are worth the
   art cost?
4. **People:** who lives there? Workers, garrison, refugees from erased towns (the lore already suggests refugees turn
   up in the next town over), townsfolk friends who move in? How do they arrive, and what do they do?
5. **Farming:** milestone 11 says farming at the home base. Fields, animals (the Ninja pack has chickens, cows, pigs,
   horses), greenhouses? How does it feed the Magic side (card dust, materials, reagents)?
6. **The Archive and the Hall** as physical places in the fortress: what do they look like, and how does entering the
   Hall feel?
7. **Raids in practice:** how often, how scary, and how the place looks after a raid (damage, repair) without being
   punishing.
8. **Progression beats:** what should a player *feel* at Camp, at Keep and at Citadel? What's the first hour in a new
   fortress like?
9. **Co-op space:** the partner's place inside the host's fortress (see CO5 above).
10. **Forward fortresses:** how different should they feel from the prime base?

## What to hand back

A design doc in the style of the existing ones (headings, short bullets, decisions marked as decided vs open), plus a
first structure list with rough costs and tiers that Grok can turn into data. Steve and Wren will turn it into roadmap
packages.
