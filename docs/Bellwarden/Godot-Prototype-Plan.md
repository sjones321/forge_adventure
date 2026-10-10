# Bellwarden card game: Godot prototype plan

Written by Wren, 2026-10-09. The goal is a **small, playable prototype** of Bellwarden's own card game, to find out
whether it's fun before any big move. The Forge-based Bellwarden stays as Steve and Tiny's playable game meanwhile.

## Decisions

- **New repository** (e.g. `bellwarden`), separate from the Forge fork. **No Forge code is copied** (clean room), so the
  licence is Steve's choice; data, art, maps and design docs from the Forge fork can be reused freely because they
  are Steve's (Kenney/LPC/Ninja assets keep their own licences, see `CREDITS.md`).
- **Godot 4 (current stable)**, desktop first (Windows, Linux), Android later.
- **Language: C#.** Agents work fastest and safest with types and a real test framework. The **rules engine and the
  card generator are plain C# libraries with no Godot dependency**, so they run headless in tests and in balance
  simulations. Godot is only the presentation layer.
  - Check C# Android export early (Phase 0). If it's not good enough for the target Godot version, keep the core
    libraries in C# and decide then (they also run on a server or in tools either way).
- **Data-driven:** keywords, effects, parts, templates, themes and sets are JSON files that Steve can edit.

## Phases

### Phase 0: skeleton (days)
- Repo, Godot project, C# solution with three projects: `Bellwarden.Core` (rules), `Bellwarden.Cards` (generator),
  `Bellwarden.Game` (Godot).
- CI building and running the Core/Cards tests; a Windows export of an empty scene; an Android export smoke test.

### Phase 1: rules core (headless)
- Implements Vex's rules v0.1: game state, turn loop, resources, combat, win/loss, a deterministic seeded RNG, and an
  **event log** (every change is an event, which later drives animations, replays, networking and undo).
- The effect library from the keyword list (damage, draw, shield, summon, buff, drain…), each effect testable alone.
- **Take back** built in from the start: undo the last action while no new information has appeared (the event log
  makes this cheap).
- A simple heuristic AI. Tests for every keyword and for full games AI vs AI.

### Phase 2: procedural cards and balance
- The generator: templates + parts + power budget + tags + naming + rules-text writer, from JSON.
- Theme deck builder: "N cards whose parts carry these tags", with a curve and resource mix.
- **Balance simulator:** thousands of headless AI-vs-AI games per theme matchup, reporting win rates and outliers, so
  point costs can be tuned with numbers instead of guesses.

### Phase 3: the fight screen (vertical slice)
- One fight, start to finish: hand, board, resources, drag to play, targeting arrows, a clear combat read, a
  counter/fizzle banner, card zoom with full text, controller and mouse, readable at 1080p.
- **Card renderer with layered art:** frame by rarity and faction, subject icon, effect overlay, generated name and
  text. Steve can swap any layer without touching code.
- Rewards screen after the fight (signature card + set cards) to feel the collection loop.

### Phase 4: co-op
- Two players vs two AI enemies over the network using Godot's high-level multiplayer (ENet), host-authoritative,
  driven by the same event log. Works over LAN and Tailscale.
- Steam later: Steam lobbies and networking via the GodotSteam plugin (needs a Steamworks app, $100 one-time); GOG
  Galaxy later still.

### Phase 5: decision gate
Steve and Tiny play it for a week. If it's fun, plan the move of Bellwarden's systems (overworld, gathering,
crafting, fortress, co-op worlds) into Godot piece by piece, reusing the data files and Tiled maps (Godot has Tiled
importers). If it isn't, little is lost and the designs improve.

## What carries over from the Forge fork

- All design docs (`docs/Adventure/*`), lore, the economy and co-op designs.
- Data: materials, recipes, structures, enemy themes (re-expressed as tags), achievements.
- Art: Steve's node sheet, town tiles, Kenney/Ninja assets; Tiled maps.
- Not code: Forge's Java engine is built around Magic and won't convert; the new rules core is much smaller.

## Risks

- **Scope creep:** the prototype is one fight plus co-op, not the whole game. Resist building the overworld early.
- **Fun is unproven:** the balance simulator and early playtests are there to answer that quickly.
- **Two projects at once:** Grok's agents can work on both; Wren keeps reviews separate.
