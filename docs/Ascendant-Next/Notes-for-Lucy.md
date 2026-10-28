# Notes for Lucy

*Reed (Cursor), 2026-10-18. Steve asked for this file in the repo so you can read it. He is continuing the research design with you. This note does not rewrite `Base-Hall-MTG-Automation-Design.md`, and it does not answer the open research question in that file.*

## Your design file is in the repo

`docs/Ascendant-Next/Base-Hall-MTG-Automation-Design.md`

That is the copy Steve uploaded from `A:\forge_ascendant_next\docs\Ascendant-Next\Base-Hall-MTG-Automation-Design.md`. The header on it says updated 2026-10-16. Where it conflicts with `docs/Adventure/Fortress-Design-Brief.md`, your file is the one to follow. Keep editing that file for Hall, cards, conjures, power, combat, co-op, and world generation. Research stays in there with you and Steve.

## What to read beside it

These are already on `feature/set-start` unless noted. They are Steve + Wren, 2026-10-13 and 2026-10-14, plus one later lock from Steve on this branch.

| File | What it locks |
|---|---|
| `AI-Onboarding.md` | Packet written so a new AI can work without re-learning the project. Planes, five colour biomes, towns, islands, layers, travel, forest mounts. |
| `Architecture-Brief.md` | Own game on Forge's engine. Hand-tailored set planes. Seamless towns, instanced interiors. Island mix. Traversal unlocks. Chunk deltas. |
| `Towns.md` | One hand-made template per biome. Steve approved the plains market ("perfect"), the island port ("lovely"), and the swamp town ("i like it"). Next proposed: mining town, then forest treehouse. His art task on the swamp town is the lift-net counterweight. |
| `Resources-Redesign-Draft.md` | Resources belong to places. Some surface outcrops; most ore is underground and does not regrow. Trees regrow over a full season. Fishing is in. Partner co-op shares the host's world. |
| `Alive-Slice.md` | Next thing Steve should feel, before more scale, digging, or building. |
| `World-Playable-Path.md` | Feel order: walkable scale, then malleable terrain, then base building, then layers. Includes the 2026-10-18 "where you build" table below. This file is on `cursor/resources-redesign-draft-c0e8` (PR #83). Its line that says there is no walk loop is stale. |
| `WG1-Spec-Stage1.md` | Approved world-gen prototype. Renders, not the walk. |

## What Steve has already walked

A stage-1 home plane exists and he has walked it (branch `cursor/ascendant-next-walk-c0e8`). Havenbrook and the plains are in that build. He said the big plane felt like paint with no life. That is why `Alive-Slice.md` shrinks the next slice to Havenbrook, the road, and the plains market: people, animals, turning sails, smoke, footsteps, town music, a day tint. Digging, building, and the other biomes wait until that walk feels like a place.

Your line that another Reed is on world-map generation, and that new features should pause while established systems get fleshed out, matches that slice. The walkable skeleton is already far enough along that the next gap is life in one five-minute walk, then chop/dig that persists.

The Havenbrook windmill sails are still wrong. Steve will fix that art himself. The stone arch is fine. Leave both alone.

## Decisions after your 2026-10-16 file

Steve said this on 2026-10-18, after a lot of Core Keeper. It is written into `World-Playable-Path.md` under "Where you build". Fold it into your world section when you next edit.

The home plane starts on the surface. What you clear up there is mostly trees. A ladder you place is how you change layer, up or down. Layers share XY with the surface. Core Keeper is the reference for down, not for the opening.

| Place | What it is for |
|---|---|
| Plains | Wide open. Build on the surface. Food is plentiful here. |
| Heavy trees | Chop on the ground. Up is already a canopy walkway. |
| Up, everywhere else | Platforms you build out from the ladder. |
| Down (land) | Core Keeper dig. Minerals in the wall, caverns, some finished rooms. The base is the hole you dig. |
| Mountains | More caves. Down should meet those, not only dirt you dug yourself. |
| Islands | Down is water you can build in. |
| Swamp | Don't count on a flat surface base. Build higher, or deal with the swamp some other way. Still open. |

## Deltas to reconcile with him

These are places your file and the older repo docs both speak. Your file wins over the fortress brief. The rows below are the ones still split, so you can settle them with him instead of both texts staying quietly different.

- **Where the base is.** Your file: digging is mainly underground, and the surface holds settlements, farms, and workshops. His 2026-10-18 table: plains are a surface base, down-on-land is the hole you dig, heavy trees already have a canopy floor, islands build in the water, swamp is open. Both can stand if each biome has its own floor. The swamp line is the one he left open on purpose.
- **Claim flags.** Your file: freeform building outside protected radii around points of interest, no land-claim flag. `World-Playable-Path.md` stage 3 still says "claim a site (or just build in the wild)". Your rule is the later one.
- **Travel.** Your file: each zone has its own method; boats are the water method, plus fast travel between docks you have established; dock costs are open. The 2026-10-13 brief already names the unlocks: plains mount, island boat, swamp skiff, mountain goat, and a forest choice of stag (over logs), panther (into the canopy), or bear (through brush). Stations: stagecoach, dock, caravan, elf guide, skiff. The panther canopy and his "heavy trees, up is already a walkway" line are the same place.
- **Wastes.** Your correction stands: the colorless Wastes are the outer edge of every colored biome, not a sixth wedge. Pair borders are real blended biomes. `AI-Onboarding.md` still describes only the five colour biomes and does not mention Wastes.
- **Villages.** `Towns.md` is the look of a living town, one template per biome, three approved. Your file adds the Death Watch reason: biomes normally have living people, abandoned sites are clues, survivors choose their own home. Abandoned and empty are placement exceptions, not a second template yet.
- **Layer count.** Your file: many descending layers, more danger with depth. The path doc: feel a 3–5 level base before locking a number; the cap should be high, and it stays tunable until chunk-delta size is measured. Sky shares XY with the surface.
- **Dungeons.** Both say procedural interiors that persist for the run. You added later reoccupation by other factions. The path doc adds one tileset flavour per biome. No fight between those.

## Left with you

Research, and how it connects to cards without rewriting card identity. Magitech unlock order and battery throughput. The duel-bubble radius. Dock costs. Raid consequences. Whether automated card crafting is its own machine. Steve said he will keep working those with you.

I have not started a dig, a base builder, or a research system from this note.
