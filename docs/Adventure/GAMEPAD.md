Basic Gamepad Support for Adventure Mode

Tested using DS4 Controller on Windows and Android.

If using on Windows OS and you have DS4Windows installed, you might experience dual input because of Emulated/Virtual Controller. To fix this you must use HidHide (better than exclusive mode). Refer to the guide here:
https://vigem.org/projects/HidHide/Simple-Setup-Guide/

DS4Windows latest:
https://github.com/Ryochan7/DS4Windows/releases/

HidHide latest:
https://github.com/ViGEm/HidHide/releases

Other XInput Controller should work, XBox or DS4 preferably and other similar XInput Controller with comparable button layout. Custom Key Mapping is not yet supported.

Controls:

- UIScenes
  - DPAD Up/Down/Left/Right - for selecting texboxes and textfield, Scroll Up or Down
  - Button A - Ok, Show Context
  - Button B - Cancel
  - Button X - Increase Difficulty in NewGame Plus/Flip Backside Deck Editor
  - Button Y - Decrease Difficulty in NewGame Plus/Zoom or Text Mode in Deck Editor
  - Left/Right Shoulder Button - Scroll Up or Scroll Down on some UIScenes

- Achievements (Bellwarden: Planes of Nothing Status → Awards)
  - DPAD Up/Down - Move between Back / Status / Skills / Quests and scroll the list
  - Button A - Activate the focused button
  - Button B - Back to Status
  - Left/Right Shoulder - Scroll the achievement list (same as other UIScenes)


- RewardScene
  - DPAD Left/Right - Selector
  - Button A - Confirm/Flip Reward
  - Button B - Show Rewards/Done
  - Button Y - Show/Hide Zoom Card


- TextInput
  - DPAD Up/Down/Left/Right - Key Selector
  - Left Shoulder Button - Shift Keys
  - Right Shoulder Button - Backspace
  - Button Start - Jump to Ok
  - Button A - Confirm
  - Button B - Cancel


- World/Gamescene
  - DPAD Up/Down/Left/Right - Character Movement
  - Left Analog - Character Movement
  - Button A - Menu/Confirm
  - Button B - Statistics/Edit
  - Button X - Deck Select/Deck Edit
  - Button Y - Inventory/Rename


- Match/Battle (stock / modern off)
  - Left Trigger - Play/Draw/OK (Bottom Left Button)
  - Right Trigger - Keep/Mulligan/Cancel/End Turn/Alpha Strike (Bottom Right Button)
  - DPAD Up/Down/Left/Right - Selector (To select cards on the battlefield, close Zone tabs first (Button B))
  - Left Shoulder - Player Panel Selector
  - Right Shoulder - Zone Selector/Show
  - Left Analog Down - Select Player (current selected panel)
  - Button A - Confirm
  - Button B - Cancel/Hide
  - Button Y - Show Zoom
  - Button Back - Show Menu Tabs

- Match/Battle — modern duel screen (Auto = Adventure duels in Bellwarden: Planes of Nothing only; or Settings → Modern duel screen = Always)

  Stock bindings above still apply when you are not in a modern pad mode. Modern modes use **X** as the mode key (unused in stock match) and **B** to back out of any modern mode. **A** on a **castable** hand card **picks it up** (view-based castability — lands you can still play, or CMC ≤ mana in pool **plus untapped lands**; the engine still validates). A second **A** on the **same** hand card or while aimed at the board **casts**; a second **A** on a **different** hand card **reorders**. **A** picks up your creature while **InputAttack** / **InputBlock** is up for attack/block aiming. Otherwise **A** is stock tap/activate (mana, abilities, loyalty, uncastable hand).

  | Action | Binding |
  | --- | --- |
  | Cast / play from hand | **A** to pick up a castable hand card, aim with **DPAD**, **A** again to cast (or **A** again on the same card to confirm). Touch: drag onto the board. |
  | Attack / block (two-press aim) | **A** to pick up your creature during InputAttack/InputBlock, aim with **DPAD**, **A** to drop on the target |
  | Stock tap / activate (no drag / not castable) | **A** (unchanged — pays mana, abilities, loyalty; uncastable hand cards) |
  | Attack a player | Hold attacker with **A** (InputAttack only), focus opponent panel (no card), **A**. Dropping on the same defender again does not toggle the attacker off. |
  | Assign several blockers to one attacker | Hold blocker **A** (InputBlock only) → focus attacker → **A**; repeat. Dropping on an attacker already blocked by that creature does not remove the block. |
  | Cancel hold / peek / mana / phase mode | **B** |
  | Press-to-peek hand | Focus a hand card, **X** — card enlarges (touch: long-press/hold; plain press scrolls the hand) |
  | Move peek between neighbours | **DPAD Left/Right** while peeking |
  | Lift peeked card to play | **DPAD Up** while peeking (then aim and **A**), or **A** while peeking to hold it |
  | Select peeked card when the engine is asking | **A** while peeking during a selection prompt |
  | Close peek | **X** or **B** |
  | Reorder hand | Touch: drag within the hand. Controller: **A** to pick up a castable hand card, focus another hand card, **A** to drop/reorder. |
  | Floating mana (while paying) | **X** when mana pips are showing (and hand is not focused) → mana mode; **DPAD Left/Right** cycle pips; **A** spends the focused pip. Tap on a pip is touch input. |
  | Phase stops | **X** when neither peek nor mana applies → phase mode on your rail; **DPAD Left/Right** cycle phases; **A** toggles stop on/off. Tap on a phase label is touch input. |
  | Target spells / abilities | During a selection prompt, **DPAD** to the target; amber arrow from the prompt source follows focus **after the pad has been used** (never at match start before input); **A** confirms (stock select). No pick-up. Touch clears stale pad focus so arrows follow the touched source. |
  | Zoom card | **Y** (controller). Touch: **double-tap** a card to zoom — the first tap is deferred so it does not select/activate; only the double-tap zooms (hand long-press peeks). |
  | Zone tabs / player panels / prompts | **R1** / **L1** / triggers (unchanged) |
