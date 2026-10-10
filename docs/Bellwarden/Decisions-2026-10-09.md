# Bellwarden: decisions so far (update for Vex)

Written by Wren, 2026-10-09, after Steve and Wren reviewed which Forge-era habits to keep for Bellwarden's own game.
Read this after `Card-Game-Brief.md` and `Godot-Prototype-Plan.md`.

## Decided

- **Bellwarden gets its own card game.** Magic was never the point; collecting cards and building decks inside an RPG
  is. The Forge build stays as Steve and Tiny's playable game and design lab until the new game proves itself.
- **Godot 4 with C#**, new repository, no Forge code. Rules engine and card generator are plain C# (testable
  headless); Godot is the presentation layer.
- **Co-op is the default**, not an add-on. Every system is designed for two players from the start.

## Tools and tech: keep, change or drop

| Forge-era choice | Decision | Reason |
|---|---|---|
| Tiled for maps | **Change** to Godot's tile editor for new maps | One editor with live preview; collision, navigation and terrain autotiling per tile. Tiled skills carry over. Tiled stays only to import existing maps. |
| Aseprite | **Keep** | Best fit for Steve's draft-and-mold workflow; Godot imports Aseprite files. |
| 16px world tiles | **Keep** | Suits drafting; Kenney and Ninja Adventure packs fit. |
| Card art at world scale | **Change** | Cards get bigger, layered art (frame + subject + effect) so they read as cards. |
| Forge save format (Java object dumps) | **Change** to versioned JSON | Readable, migratable, written safely (temp file then swap). |
| Custom networking (session codes, Tailscale, protocol numbers) | **Change** to Godot multiplayer | LAN and Tailscale now, Steam lobbies later. |
| One giant generated overworld | **Reconsider**: hand-made hubs + generated wilds | Towns and the fortress are hand-made places; the land between them is generated. Fits the Stardew feel. |
| Forge's pop-up dialogs | **Change** to one dialogue system (e.g. the Dialogic plugin) | Conversations, choices and townsfolk all in one place. |
| Card data and images from outside (Scryfall) | **Drop** | All cards are ours: generated plus hand-made, with our own art. |
| Real-world prices, ban lists, rotation tied to real sets | **Drop** | Game-based economy; our own planes and sets. |
| JSON data files (materials, recipes, themes) | **Keep** | They port as-is and stay editable. |
| Maven / Java | **Drop** | Godot + C#. |
| Safety habits (isolated test folders, backups, never writing real saves in tests) | **Keep** | Non-negotiable. |

## New open problem: progression without 30 years of Magic sets

Forge gave us decades of sets for free: every plane was a real set, rotation came from real release history, and
collecting had near-endless depth. Our own game needs its own answer. Ideas to start from (none decided):

- **Planes are sets, and the procedural generator makes them deep.** Each plane gets a theme, factions, two or three
  signature mechanics and a parts list; the generator fills hundreds of on-theme cards. A handful of hand-made
  legendaries and signature cards per plane give it identity.
- **New planes over time** (seasons or updates) extend the chain; the Nothing erasing planes gives rotation a story
  reason instead of a release calendar.
- **Depth through combination:** cards gain variety from parts, rarities, prefixes and suffixes (Borderlands-style),
  so two copies of a "type" of card can still differ, which makes chasing a perfect roll a goal.
- **Collection goals beyond owning:** set mastery, card mastery (cards level up as you play them), card styles,
  achievements, and townsfolk signature cards.
- **Crafting as authorship:** players craft cards from parts and materials (choose the template, slot parts within a
  power budget), which turns gathering into deck design.
- **Prestige** resets with permanent cosmetics and unlocks, so the collection loop can restart with new goals.

Questions for Vex: how many cards and mechanics a plane needs to feel complete; how fast new planes should arrive;
whether rolled card variation is fun or noise for a collector; and how crafting cards from parts stays balanced.
