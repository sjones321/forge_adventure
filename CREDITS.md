# Credits

This fork builds on a lot of other people's work. This file lists everything we use that we didn't make, with its
license. **Add an entry here whenever a new asset, library or data source is added.**

## Forge

- **Forge** — the MTG rules engine, card scripts, AI, desktop and mobile clients, and Adventure mode this project is
  built on. Copyright the Forge developers and contributors. GPL-3.0.
  <https://github.com/Card-Forge/forge>
- **Particle Park "Forge effects"** (`forge-gui/res/adventure/common/particle_effects/`) — Raymond Buckley. CC BY 4.0.
  See `Particle Park License.txt` in that folder.

## Match UI behaviour (adapted, not copied)

- **Neo Forge** (<https://github.com/AdrianLopez98/NeoForge>, GPLv3, JavaFX) — behaviour reference for Bellwarden: Planes of Nothing
  package DS1 (modern libGDX duel screen). Studied and reimplemented in `forge-gui-mobile` (not a line copy):
  - Combat / target / drag arrow colours and edge-to-edge curved arrows (`CombatOverlay.java`)
  - Drag-to-cast, drag-to-attack/block, ~9px drag slop, cancel when dropping a hand card off the board
    (`TableScreen.installDragGestures`, `NeoMatchUI.onCardDropped`)
  - Press-to-peek hand, slide between hand cards, push up onto the table to play (`TableScreen` hand peek)
  - Drag-to-reorder hand via `IGameController.reorderHand`
  - Clickable floating mana pips near the player field (`PlayerBar` mana pool)
  - All inputs still go through `IGameController.selectCard` / `selectPlayer` so co-op
    (`RemoteClientGuiGame` / CO3) stays on the existing network path

## Sound effects

- **Kenney — Impact Sounds** and **RPG Audio** (gathering sounds in `forge-gui/res/adventure/common/sound/`) —
  Kenney Vleugels, <https://kenney.nl>. CC0 1.0 (public domain); credited with thanks.
  License files: `LICENSE-kenney-impact-sounds.txt`, `LICENSE-kenney-rpg-audio.txt`.

## Map tiles

- **Kenney — Tiny Town, Tiny Battle, Tiny Dungeon** (16px tilesets in
  `forge-gui/res/adventure/common/maps/tileset/kenney_*.png`) — Kenney Vleugels, <https://kenney.nl>. CC0 1.0;
  credited with thanks.
- **Kenney Tiny Town tool icons** (local tiles 115 pickaxe, 127 axe, 128 hammer/chisel, 129 sickle,
  130/131 buckets for probe) and tinted Mana dust icons live in the Planes of Nothing–only sheet
  `forge-gui/res/adventure/common/sprites/ascendant_items.png` / `ascendant_items.atlas`
  (`ToolPickaxe`, `ToolAxe`, `ToolHammer`, `ToolSickle`, `ToolBucket`, `ToolBucketFull`,
  `DustCommon` / `DustUncommon` / `DustRare` / `DustMythic`). Same CC0 1.0 Tiny Town source.
  Stock `items.png` / `items.atlas` are unchanged.

## Art (downloaded, not yet in the game)

These packs are kept outside the repo in `A:\GameAssets` for upcoming art work. Credit moves into the sections above
when an asset is actually used.

- **Kenney — Interface Sounds, UI Pack** — Kenney Vleugels,
  <https://kenney.nl>. CC0 1.0.
- **Ninja Adventure - Asset Pack** — Pixel-boy and AAA, <https://pixel-boy.itch.io/ninja-adventure-asset-pack>.
  CC0 1.0; attribution not required but given with thanks. Animated characters, animals and monsters, tilesets,
  effects, items, sounds and music. Kept in `A:\GameAssets\NinjaAdventure`.
- **Liberated Pixel Cup (LPC) Base Assets** — <https://opengameart.org/content/liberated-pixel-cup-lpc-base-assets-sprites-map-tiles>.
  Dual-licensed **CC BY-SA 3.0** and **GPL 3.0**. Authors (per the pack's `CREDITS.TXT`):
  - Lanea Zimmerman (Sharm) — base tileset (also OGA-BY 3.0)
  - Stephen Challener (Redshrike) — character templates (also OGA-BY 3.0)
  - Charles Sanchez (CharlesGabriel)
  - Manuel Riecke (MrBeast)
  - Daniel Armstrong (HughSpectrum)

  Any LPC art used in the game must keep this attribution and its share-alike license, and the file-by-file list
  from `CREDITS.TXT` goes into the repo next to the art.

## Setting and story

- **The Eternal Engine** cosmology and the game's original lore (`docs/Adventure/Lore.md`) — Steve Jones.

## Magic: The Gathering

This project is unofficial Fan Content permitted under the Wizards of the Coast Fan Content Policy. Not approved or
endorsed by Wizards. Portions of the materials used are property of Wizards of the Coast. ©Wizards of the Coast LLC.

Card images are not included; they are downloaded at runtime by Forge from third-party sources (such as Scryfall).
