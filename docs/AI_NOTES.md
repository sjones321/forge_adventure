# Adventure AI observations (from playtesting)

Running list of AI behavior Steve noticed while playing. Each entry: what happened, the card(s),
whether it's actually a misplay, and a possible fix.

| Date | What happened | Cards | Verdict | Possible fix |
|---|---|---|---|---|
| 2026-10-06 | AI pinged Steve's Momo (1 toughness) right after it was cast, giving Steve its leaves-the-battlefield trigger | Momo, Playful Pet (TLA) vs. Fireslinger (repeatable: T: 1 damage to any target, 1 to itself) | Not a misplay: a repeatable pinger killing a 1/1 flier is worth giving up a Food/counter/scry 2. | General gap: Forge AI rarely weighs the opponent's death/LTB triggers when picking removal targets. Could be taught per card via AI hint SVars in card scripts, or in the damage/removal target scoring. |
