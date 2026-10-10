# Bellwarden card game: design brief (for Vex)

Written by Wren, 2026-10-09. Steve wants to design Bellwarden's **own** card game, so the game can one day be
released (free, on Steam or GOG) instead of running on Magic. This brief is everything you need to start; you don't
have to read the repo.

## Why

- **Collecting and deck building inside an RPG is the point, not Magic itself.** Steve realised the fun is "a full TCG
  combat where you have to collect cards", wrapped in gathering, crafting, a fortress and co-op with his friend Tiny.
- Bellwarden today runs on Forge (an open-source Magic engine). That can never be released: Magic is Wizards of the
  Coast's IP. It also brings Forge's baggage (a complex rules engine, a dated duel UI, 30,000 cards to balance around).
- The current Forge build stays as Steve and Tiny's playable game and design lab while this is designed and
  prototyped (see `Godot-Prototype-Plan.md`).

## What already exists and must fit

- **Setting:** Steve's own cosmology, the Eternal Engine (`docs/Adventure/Lore.md`): the player is a Death Watch
  Initiate fighting **the Nothing**, which permanently erases land and towns; Harmonic Bells protect people; the Hall
  of Remembrance keeps what was lost. Cards should be able to carry that world (factions, planes, the Nothing's
  minions).
- **Planes:** the world is a chain of planes. Today each plane is a Magic set; in the new game **a plane is a card
  set** with its own themes, creature types and mechanics.
- **Collection progression:** cards come from fights (one signature card from the enemy plus cards from the current
  set), packs, shops, crafting (dust made from gathered materials) and friendships with townsfolk (each townsperson
  plays a deck that says who they are, and their top friendship reward is their signature card).
- **Formats as card pools:** a rotating "Standard" (planes in alignment), an eternal "Historic", a commons-only format
  and a singleton "Commander"-style format, each with a two-player co-op version. Each plane picks its format.
- **Enemies are themed decks:** creature types with two or more themes each (merfolk tribal, merfolk tempo, krakens…);
  in co-op, enemies come in pairs of the same type with different themes.
- **Co-op is part of the definitive game.** Two friends fight side by side against AI (and, rarely, each other). Every
  mechanic has to work with two players.
- **The economy is the game's own** (no real-world prices): card value comes from rarity, power and demand.
- **Art:** 16-pixel tiles in the world; cards need art that can be **layered and composed** (frame + subject +
  effect), because Steve has aphantasia and works by editing drafts, not drawing from blank.

## The big idea: procedural cards, like Borderlands guns

Most cards are **assembled from parts**, so every plane can have hundreds of on-theme cards without hand-designing each:

- **Template:** card type (creature, spell, item, location…) and a base stat line.
- **Parts:** effects drawn from an effect library (deal damage, draw, shield, summon, buff, drain…), each with a
  **point cost**; keywords; triggers (when played, when it dies, each turn).
- **Power budget:** the card's cost buys a number of points, rarity adds a little budget or a special part, and
  drawbacks refund points. This is what keeps random cards fair.
- **Tags:** every part carries tags (merfolk, burn, undead, tempo, Nothing…). A theme deck is "generate cards whose
  parts carry these tags", so on-theme decks come for free.
- **Names and text** are generated from the parts (prefixes and suffixes like "Searing …" or "… of the Deep"), and the
  rules text is written from the parts too, so it is always accurate.
- **Hand-made cards sit on top:** signature cards, legendaries, story and townsfolk cards are written by hand.

## Questions for you and Steve to answer

1. **Resources:** lands like Magic, a growing energy pool like Hearthstone, or something new? (It should allow
   colour/faction identity and deck-building tension without mana screw.)
2. **Card types and the board:** creatures in lanes, a free board, or rows? Items and locations?
3. **Turn structure and interaction:** how much happens on the opponent's turn (instant reactions, a simple "response"
   window, or none)? Forge's stack-and-priority depth is out of scope; we want readable, fast fights.
4. **Win condition and fight length:** life totals? Target 5-10 minute fights against AI.
5. **Keywords:** a starting list of about 15-25, each with a point cost.
6. **Factions/colours:** how many identities, what each one is about, and how they mix.
7. **Rarity:** what common, uncommon, rare and mythic mean for parts and budget.
8. **Co-op fights:** shared board or one board each? Shared life? How do two players and two enemies take turns?
9. **Deck size and limits** per format (and what "Commander" becomes in our own game).
10. **The Nothing as a mechanic:** can the Nothing erase cards, lanes or turns in a fight the way it erases land?

## What to hand back

- A rules document v0.1 (one page if possible: turn structure, resources, combat, win condition).
- The keyword and effect list with point costs, and the power-budget formula.
- Card anatomy (what fields a card has) and the procedural grammar (templates, parts, tags, naming), with three worked
  examples of generated cards at different rarities.
- Two sample theme decks built from the grammar (for example a merfolk tempo deck and a Nothing horde deck).
- Your open questions. Steve and Wren turn it into a prototype plan for Grok.
