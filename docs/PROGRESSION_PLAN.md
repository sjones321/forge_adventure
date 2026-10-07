# Adventure Progression Plan (skills)

Goal: RuneScape-style progression. Numbers always go up, everything you do earns something,
you can be self-sufficient (craft what you need), and the grind is respected: time played
always converts into power or access at a fair rate.

## 1. Core rules

- **Many skills, levels 1–99**, RuneScape XP curve (each level costs ~10% more than the last;
  level 92 is half of 99). Early levels come fast, late levels are a long-term goal.
- **XP comes from things you already do**: duels, opening packs, exploring, shopping, crafting.
  No skill requires a separate chore loop.
- **XP drops**: a small "+35 Dueling XP" popup after each action (Adventure already has
  status-message popups), and a level-up notice.
- **Skills screen**: a tab listing every skill with level, XP bar, and the next unlock.
- **Storage**: XP is stored in the existing character-flag map in the save file. No save format
  change; old saves start every skill at level 1.
- **Skills are permanent across NG+.** NG+ already keeps the character (collection, gold,
  character flags) and regenerates the world, so skill XP carries over with no extra work.
  Town levels live in the world's state and reset with each new world. Skills are the
  account-like long-term progression; each NG+ world is fresh towns to build up.
- **Pace target (tunable):** level 50 in a main skill over about one full playthrough;
  level 99 spans several NG+ cycles; early levels come fast.
- **Solo-first.** Forge Adventure has no multiplayer today. Co-op with Tiny would be its own
  large project; nothing here blocks it later.

## 2. Skills

### Combat
| Skill | XP from | Unlocks |
|---|---|---|
| **Dueling** | Winning duels (more for harder enemies); small XP for losses | +max life at milestones, better gold per win |
| **White / Blue / Black / Red / Green Magic** (5 skills) | Winning with a deck containing that color, split by the deck's color share | Perks themed on the color's strengths (e.g. White: life/healing, Blue: card selection and Spell Smith control, Black: trade life for power/gold, Red: aggression/speed, Green: bigger rewards/growth). **Pair perks**: reaching a level in two colors unlocks a perk for that two-color combination (the ten guild pairs). That color's Spell Smith discounts. |

### Gathering
| Skill | XP from | Unlocks |
|---|---|---|
| **Collecting** | Each new card added to the collection; more for rarer and first copies | Extra card in reward screens, better autosell value |
| **Exploration** | Discovering towns, caves, dungeons; clearing a dungeon | Move speed, larger map reveal radius, hidden chests |
| **Salvaging** | Selling or shredding cards into gold and shards | Better sell price and shard ratio |

### Artisan
| Skill | XP from | Unlocks |
|---|---|---|
| **Spellsmithing** | Each Spell Smith pull (more for narrower filters) | Discounts; more control over the pool: extra filters (type, keyword), narrower filters, seeing more candidates before committing. Later recipes: re-roll a pull, upgrade a card to an alt-art printing, craft a specific common/uncommon outright. **Does not unlock sets**: the Spell Smith pool is limited to sets the player has unlocked, and new sets come from new planes. |
| **Bartering** | Gold spent and earned in shops | Shop discounts and better sell prices (through the existing price hook) |

### Social
| Skill | XP from | Unlocks |
|---|---|---|
| **Tournaments** | Entering and winning inn events | Lower entry fees, better prizes, more event types |

### Towns (not a player skill)
Every town already tracks **reputation**. Turn it into a visible **town level**:
- Raised by winning duels in or near the town, finishing its quests, spending in its shops.
- Higher town level: inn events appear more often and with better formats, shops restock and
  discount, and eventually unique stock (set packs, alt arts).

## 3. Open decisions (Steve)

1. ~~**Pace.**~~ Decided: skills carry over NG+; 50 ≈ one playthrough, 99 ≈ several NG+ cycles.
2. ~~**Spell Smith gating.**~~ Decided: sets come mostly from new planes; Spellsmithing levels
   give discounts and more/narrower pool control, not set access.
3. ~~**Color skills.**~~ Decided: five separate skills, themed on each color's strengths, with
   pair perks for two-color combinations.
4. **Death penalty.** Default until Steve says otherwise: losing never costs XP; losses earn a little.

## 4. Build order

| Phase | Contents | Size (with Claude) |
|---|---|---|
| P1 | Skill framework, XP curve, XP drops, skills screen; Dueling, Collecting, Exploration, Spellsmithing with first unlocks | ~2 sessions + playtest |
| P2 | Color skills, Salvaging, Bartering (shop prices) | ~1–2 sessions + playtest |
| P3 | Town levels from reputation: inn event odds, shop discounts/restocks | ~2 sessions + playtest |
| P4 | Spellsmithing recipes: re-roll, alt-art upgrade, direct craft | ~2 sessions + playtest |
| P5 | Tournaments skill and event tuning | ~1 session + playtest |

Each phase is playable on its own; tuning happens from playtest feedback.

## 5. Formats, rotation and New Game+ (agreed with Steve 2026-10-06)

### Standard (60-card) with rotation
- Three sets are legal at a time (the "window"). A new game starts with **two** sets; the sealed start
  opens the normal pack count **in each** of them.
- Each NG+ adds a set; when a fourth arrives, the oldest rotates out.
- Rotated cards leave the active collection unless a reprint keeps them legal, or they were moved to a
  vault first (Commander Vault or Historic Vault).
- **Shops** sell mostly the current window, plus a **rotating staples pool** (useful cards from any set)
  that changes on rotation.

### Vaults
- **Commander Vault**: one-way. Vaulted cards never rotate but are Commander-only.
- **Historic Vault**: same idea for rotated Standard cards, used by a Historic format.
- **Crafting exception**: a vaulted card can be crafted as a new copy for Standard only while it is
  legal in the current window, at full cost with no skill discounts.

### Decks
- 10 Standard deck slots and 10 Commander slots (Historic gets its own).
- Decks are never deleted. If rotation removes cards a deck needs, the deck becomes **locked/archived**
  (viewable, not playable) and unlocks again if the cards come back (reprint, craft, vault).
- Switching active format moves the active deck to storage and makes the other format's deck active.
- (Replaces the earlier "retire after 2 uses" idea; rotation provides the pressure.)

### New Game+
- Goal: **set mastery** of the newest set (threshold TBD: one of each card vs. playsets).
- Carries over: skills, vaults, stored decks (locked if cards rotated), cards still legal.
- Commander becomes available after at least 2 sets have been played.

### Ban lists
- One editable file per format; banned cards can't be added and locked decks show why.

### Release
- Fork stays GPL v3 (same as Forge). LLM opponent ships off by default with an options screen where
  each player enters their own OpenAI-compatible endpoint, model and key.

### Decided (2026-10-06, second pass)
- **Mastery** of the newest set = 4x each common/uncommon, 2x each rare, 1x each mythic. It unlocks the
  next set immediately (rotation can happen mid-world), but only **one set per world**; after that the
  next unlock needs NG+.
- **Staples** are curated per format (`common/staples_standard.txt`, `common/staples_commander.txt`);
  a different subset of 12 is active per rotation. Command Tower and Arcane Signet are given free when
  switching to Commander.
- **Sealed start** = 10 packs of the starter set + 10 packs of a chosen core set (5 of each opened).
  "Core Set Collection" is a custom pack drawing from all core sets (M10–M21, ORI, FDN).
- **Enemies keep their themed decks** (not filtered by the window). Their decks may need tuning for
  the 40-card early game.
- **Inn drafts** should draw from the window, ideally letting the player choose the set.

### Built so far
- Standard window tracking + save/load, mastery progress on the Skills screen, mid-world unlock and
  rotation of the window, shop/loot/Spell Smith filtering to window + staples, sealed start with core
  set picker and Core Set Collection packs.

### Commander Vault (built)
- Deck editor: "Move to Commander Vault" on collection cards (only copies not in decks/auto-sell);
  read-only "Commander Vault" tab. Vaulted copies are hidden from non-Commander deck building, can't
  be sold or auto-sold, and are saved with the character. Usable once Commander decks exist in a save.

### Per-deck formats, Historic Vault, rotation, ban lists (built)
- Each deck slot is Standard, Commander or Historic (Std/Cmdr/Hist button on the deck screen); the
  selected deck sets editor rules and duel format. First Commander deck grants Command Tower and
  Arcane Signet into the Commander Vault.
- No Historic Vault (Steve's call): **Historic = every card you own except the Commander Vault**;
  **Standard = only currently legal cards** (window sets + this rotation's staples + basic lands).
  Nothing moves on rotation; rotated cards simply stop being Standard-legal and become legal again if
  their set or a reprint returns. Standard decks with rotated cards show "Locked".
- The Commander Vault remains the one one-way move.
- Ban lists: common/banned_standard.txt, banned_historic.txt, banned_commander.txt.
- Debug console: "allow unlock" (test a second unlock in one world).

### Color perks and skill staples (built)
- Color perks at 15/40/75 per color (duel-start life, tokens, mana shards, opening hand, opponent
  life/hand; Spell Smith discount, walk speed, bonus card rewards). Shown on the Skills screen.
- Skill staples, always Standard-legal once unlocked and offered in shops/loot/Spell Smith:
  common/staples_<color>.txt (color skill levels 10-90), staples_colorless.txt (Spellsmithing),
  staples_multicolor.txt (two cards per color pair at 25/60; both colors must reach the level),
  staples_lands.txt (Exploration: gain lands 20, temples 35, check lands 50, shocks 70, fetches 85),
  staples_utility_lands.txt (Exploration + a color or Spellsmithing, e.g. Bojuka Bog at 30/Black 30).

### Not built yet
- Blocking duels with a Locked deck (currently only marked); deck slots per format with
  lock/archive; crafting a vaulted card; Commander switch and freebies; ban list files; inn draft set
  choice; choosing the next set instead of random.

## 6. Status

- **Built (untested by Steve yet):** skill framework, RuneScape XP curve, XP drops, level-up
  notices, Skills screen (from Status and Quests), saving. Skills: Dueling (+1 max life per 10
  levels), five color skills (XP only, perks pending), Collecting (XP only), Exploration (+0.25%
  move speed per level), Salvaging (+0.5% sell price per level), Spellsmithing (−0.4% pull cost
  per level), Bartering (−0.3% shop price per level).
- **Next:** color perks and pair perks (needs Steve's input), Collecting perk, town levels.
