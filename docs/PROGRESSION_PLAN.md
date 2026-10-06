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

## 5. Status

- **Built (untested by Steve yet):** skill framework, RuneScape XP curve, XP drops, level-up
  notices, Skills screen (from Status and Quests), saving. Skills: Dueling (+1 max life per 10
  levels), five color skills (XP only, perks pending), Collecting (XP only), Exploration (+0.25%
  move speed per level), Salvaging (+0.5% sell price per level), Spellsmithing (−0.4% pull cost
  per level), Bartering (−0.3% shop price per level).
- **Next:** color perks and pair perks (needs Steve's input), Collecting perk, town levels.
