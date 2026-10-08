# Game Vision — a co-op Magic adventure built on Forge

Working title: **TBD** (must not include "Magic" or Wizards of the Coast branding).

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

### Home plane
- Your **home base** (the homestead) lives on a persistent home plane: stations, storage, vault, trophy room,
  farm and outposts. You always come back here; nothing on it resets except by prestige.

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
- **Gyms and the League** per run format (built), tournaments and Grand Prix (planned).
- **Prestige**: an opt-in full reset (collection, gold, materials, skills, planes) that keeps unlocked staples,
  the Hall of Fame, cosmetics and the prestige tree. **Prestige XP scales with how far you went**: delve depth
  reached, planes mastered, League titles, total level. Spend it on the account-wide prestige talent tree.

## Co-op: shared world (decided)

Both players are in the **same world at the same time**. The host's save owns the world; each player keeps their
own character (collection, decks, skills, materials, items). The host is authoritative over the world (enemies,
nodes, POI changes, loot rolls). Players **team up or go their separate ways**: party up by invite, leave any time,
and nobody is ever pulled into a fight or a location without saying yes. Co-op fights are two humans on one team
against the enemy, using Forge's existing network duel code. Connection is direct over LAN or Tailscale, no servers. See roadmap packages CO1-CO4.

## Milestones

1. **Co-op foundation**: session and connection (CO1), shared overworld (CO2), co-op duels (CO3).
2. **Own identity**: name, launcher, app, separate from stock Forge.
3. **Controller-first** outside duels.
4. **Multiverse core**: multi-plane save, home plane, planar portals, first generated set plane.
5. **One hand-made town** as the art test.
6. **Delves** with depth and prestige XP.
7. **Co-op systems** (CO4): shared home base, trading, co-op gyms and delves.
8. **Controller support in duels.**
9. **Stardew systems**: calendar and seasons, townsfolk friendships, farming at the home base.

## Legal guardrails

- Never charge money or put anything behind a paywall.
- No Wizards logos or "Magic" in the name or branding.
- Card images are downloaded at runtime, never shipped.
- Original art for the world, characters and towns is ours.
