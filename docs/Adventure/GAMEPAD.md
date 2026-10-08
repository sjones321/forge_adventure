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

- Match/Battle — modern duel screen (Ascendant default, or Settings → Modern duel screen = Always)

  Stock bindings above still apply when you are not in a modern pad mode. Modern modes use **X** as the mode key (unused in stock match) and **B** to back out of any modern mode.

  | Action | Binding |
  | --- | --- |
  | Pick up / drop card (cast, attack, block, reorder) | **A** on focused card; **A** again on drop target |
  | Attack a player | Hold attacker with **A**, focus opponent panel (no card), **A** |
  | Assign several blockers to one attacker | Hold blocker **A** → focus attacker → **A**; repeat with the next blocker |
  | Cancel hold / peek / mana / phase mode | **B** |
  | Press-to-peek hand | Focus a hand card, **X** — card enlarges |
  | Move peek between neighbours | **DPAD Left/Right** while peeking |
  | Lift peeked card to play | **DPAD Up** while peeking (then aim and **A**), or **A** while peeking to hold it |
  | Select peeked card when the engine is asking | **A** while peeking during a selection prompt |
  | Close peek | **X** or **B** |
  | Reorder hand | Hold a hand card with **A**, **DPAD** to another hand slot, **A** to drop |
  | Floating mana (while paying) | **X** when mana pips are showing (and hand is not focused) → mana mode; **DPAD Left/Right** cycle pips; **A** spends the focused pip |
  | Phase stops | **X** when neither peek nor mana applies → phase mode on your rail; **DPAD Left/Right** cycle phases; **A** toggles stop on/off |
  | Target spells / abilities | During a selection prompt, **DPAD** to the target; amber arrow follows focus; **A** confirms (stock select). No pick-up needed. |
  | Zoom card | **Y** (unchanged) |
  | Zone tabs / player panels / prompts | **R1** / **L1** / triggers (unchanged) |
