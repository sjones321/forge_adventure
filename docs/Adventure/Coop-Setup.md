# Ascendant co-op setup (CO1)

Direct, no servers. One player **hosts**; the other **joins** over LAN or Tailscale.
Ascendant only — stock Forge / non-Ascendant Adventure worlds do not show Host / Join.

## Building it on a second PC (for the guest)

Both players must run the **same commit**. Builds made separately on each PC match; the check is the Forge
version, the co-op protocol version and the loaded card data, not the build time.

Requirements: **Java 17** (JDK) and **Maven 3.9+** on the PATH, plus Git.

```
git clone https://github.com/sjones321/forge_adventure.git
cd forge_adventure
git checkout feature/set-start
git log -1 --format=%H        # compare this hash with the host's; they must match
mvn -B install -DskipTests -pl forge-gui-mobile,forge-gui-mobile-dev -am
```

The first build downloads dependencies and takes a while; later builds can add `-o` (offline).

Run the game with **`play-adventure.cmd`** in the repo root (Windows). It starts Forge from the `forge-gui` folder
so it finds its `res` data, with the Java options Forge needs. In Adventure, start or load a **Shandalar Ascendant**
game; co-op only exists in that world. Then follow **Join** below.

To update later: `git pull`, check the commit hash matches the host's again, and rebuild.

## Ports

| Port  | Role |
|------:|------|
| **36744** | Ascendant co-op overworld session (handshake, world sync, CO2). |
| **36743** | Forge game / duel port — started **only for co-op duels** (CO3), bound to `coopBindAddress` when set, same session auth as 36744. Stock online play is unchanged. |

Tunable in the Ascendant `config.json` as `coopOverworldPort` / `coopGamePort`.

## Host

1. Play **Shandalar Ascendant**, Load or Continue a save (the host's save owns the world).
2. From the Adventure main menu, tap **Host**.
3. Note the **8-character session code** on the Hosting screen — the guest must type it.
4. Share one of the listed addresses (LAN IP or Tailscale `100.x`) plus that session code.
5. Use **Stop** on the Hosting dialog to end the session and shut down the overworld listener.
6. Default hosting **skips UPnP** (`coopSkipUPnP: true`). Prefer Tailscale, or open the port manually.

### Optional bind address

By default the host listens on **all interfaces**. To bind only to a specific address (recommended for Tailscale), set in Ascendant `config.json`:

```json
"coopBindAddress": "100.x.y.z"
```

Use your Tailscale IPv4 (`100.x`). Leave empty (`""`) for all interfaces.

## Join

1. Load or Continue **your** character on the guest PC (collection, decks, skills stay local).
2. Tap **Join** and enter:
   - Host address: Tailscale `100.x.y.z` or LAN IP (port defaults to 36744)
   - **8-character session code** from the host screen
3. On success the guest rebuilds the world from the host's seed into a **separate session world** (your normal save slots are never overwritten) and verifies a hash. If the hash does not match, co-op refuses with *"Builds or world data differ; update both copies"* and disconnects — there is no world-blob fallback.
4. On disconnect your normal save is restored; your co-op character file under `characters/` is updated and kept for the next join (solo saves are never overwritten by co-op). First join seeds that file from your solo player once.

## Windows firewall

Run these in an **Administrator** PowerShell on the **host** PC.

Scope rules to the Java/Forge program and to Tailscale or the local subnet — do not open 36743/36744 to the whole Internet.

**Tailscale** (recommended):

```powershell
# Run as Administrator
New-NetFirewallRule -DisplayName "Forge Ascendant Co-op Overworld (Tailscale)" `
  -Direction Inbound -Protocol TCP -LocalPort 36744 -Action Allow -Profile Private `
  -RemoteAddress 100.64.0.0/10 `
  -Program "C:\Path\To\java.exe"

New-NetFirewallRule -DisplayName "Forge Ascendant Co-op Duels (Tailscale)" `
  -Direction Inbound -Protocol TCP -LocalPort 36743 -Action Allow -Profile Private `
  -RemoteAddress 100.64.0.0/10 `
  -Program "C:\Path\To\java.exe"
```

Replace the `-Program` path with the `java.exe` (or Forge launcher) you actually run.

**LAN (local subnet only):**

```powershell
# Run as Administrator
New-NetFirewallRule -DisplayName "Forge Ascendant Co-op Overworld (LAN)" `
  -Direction Inbound -Protocol TCP -LocalPort 36744 -Action Allow -Profile Private `
  -RemoteAddress LocalSubnet `
  -Program "C:\Path\To\java.exe"

New-NetFirewallRule -DisplayName "Forge Ascendant Co-op Duels (LAN)" `
  -Direction Inbound -Protocol TCP -LocalPort 36743 -Action Allow -Profile Private `
  -RemoteAddress LocalSubnet `
  -Program "C:\Path\To\java.exe"
```

Also confirm the **Tailscale adapter's network profile is Private** (Windows Settings → Network & Internet → the Tailscale connection → Private). A Public profile can still block inbound rules even when the rule exists.

## Tailscale vs LAN / UPnP

- **Tailscale** (`100.64.0.0/10`, treated as any `100.x` IPv4): both PCs on the same Tailnet; **do not use UPnP**. Join with the host's Tailscale IP and session code. Prefer binding the host to that `100.x` address via `coopBindAddress`.
- **LAN**: same subnet; use the LocalSubnet firewall rule above. UPnP is skipped by default for co-op.
- **WAN without Tailscale**: not supported for CO1.

## Version / session mismatch

Co-op uses a **hard** check (classic online only warns):

- Matching **8-character session code** (5 failed attempts from one address → 5-minute lockout; host stays **HOSTING**)
- Same Forge **build hash** (Forge version + co-op protocol version; building the same commit on each PC matches)
- Same loaded **card data hash** (name + edition + art index + oracle + ability/script lines)

If any differ, the host rejects and closes the guest channel. The Hosting screen stays up so you can try again. Update both installs so build and card data match, then retry with the current session code.

World seed rebuild must also produce the same world hash; otherwise the guest refuses with *"Builds or world data differ; update both copies"* and disconnects.

## Character vs world

| | Host | Guest |
|---|---|---|
| World (map, enemies, POIs, nodes) | Owns / saves | Held in a co-op **session world** only; normal save slots untouched |
| Character (collection, decks, skills, materials, items) | Local | Local — loaded from / saved to `adventure/<plane>/characters/` |

## CO2 / CO3

**CO2 (shared overworld)** builds on this session: position sync, partner sprite,
host-authoritative nodes/enemies/POI, party invites, and location-enter invites.
Tunables live in Ascendant `config.json` (`coopPositionSendHz`,
`coopPartnerInterpRate`, `coopInteractRangePx`, `coopLocationInviteTimeoutSeconds`, …).
Wire protocol: CO2 = 5; CO3 = 6; MV2 mid-session gate-delta = **9** (TR1 takes the next number).

**Interior rule (v1):** both roam freely on the overworld. Entering a town,
dungeon or delve invites a nearby party partner. Accept → enter the same
interior when ready (accepter marked inside only on actual enter); decline /
timeout → wait outside. Only one shared interior at a time. While the **host**
is in an interior or a duel, enemy AI / spawns / lifetimes pause for both; the
guest keeps free movement and sees a persistent "Host is in …" banner (dedicated
HUD label). In-game Party button (P / left-stick click) invites or leaves;
incoming party and location invites use Accept/Decline dialogs (keyboard, mouse,
and controller). Console remains as a debug path:
`coop party invite|accept|decline|leave`, `coop location accept|decline`.

**Guest is a pure mirror:** no local enemy AI, spawns, lifetime expiry, or local
fights against mirrored enemies. Guest collisions send one
`CoopEnemyEncounterRequestEvent` per mob per contact (host rate-limits; no HUD
spam). Guest gather: host confirms claim; guest applies the normal solo reward
path locally (round 3 item 6).

**Movement:** walk speed limit is the peer's actual max (base × road ×
equipment/skill) × margin. Waypoint / portal / reset / POI exit send an explicit
teleport sample (accepted only after an allowing action).

**Disconnect:** guest removes mirrored sprites; host only clears id maps (real
entities stay). Guest stashed enemies are restored on leave.

**Menus:** inventory / deck editor do **not** pause the shared overworld in co-op
(but duels do — background tick never despawns the fought mob).

**CO3 (co-op duels):** starts game port (**36743**) only when a joined co-op duel
begins, binds to `coopBindAddress` when set, gates LoginEvent to the authenticated
guest / session code, and stops it when the duel or session ends. Hooks:
`CoopHooks.notifyFightAboutToStart(String)`,
`CoopHooks.notifyGuestEnemyEncounter(long, String, String)` /
`GuestEnemyEncounterHandler`, and party proximity via
`CoopPartyState.inParty()` / `withinRadius(...)` → `CoopHooks.setPartyProximity`.

See `CoopHooks` / `CoopOverworldRuntime` / `CoopDuelRuntime` and
`docs/Adventure/Ascendant-Roadmap.md`.

**Trust note (CO3 decks):** the host cannot verify that the guest owns the cards in their decklist — the partner is trusted. The host still clamps loadout life/hand, allowlists effect card names from items.json / skill perks, and enforces Adventure min deck size + ban lists.

## One-PC playtest (two Forge copies)

Use this to manually verify co-op duels on a single machine before review.

### Separate profile / data dirs

Run two copies with **different** `userDir` values so saves and prefs do not collide. Easiest: two install folders, each with its own `forge.profile.properties`:

```properties
# host install: forge.profile.properties
userDir=/home/you/forge-coop-host/

# guest install: forge.profile.properties
userDir=/home/you/forge-coop-guest/
```

Then launch each install's jar from its own folder (two terminals). On Windows use e.g. `C:\ForgeCoop\host\` and `C:\ForgeCoop\guest\`.

### Ports

Defaults: overworld **36744**, game/duel **36743**. On one PC both sides share localhost, so leave ports at defaults on the host. Optional in Ascendant `config.json` on the **host**:

```json
"coopBindAddress": "127.0.0.1",
"coopOverworldPort": 36744,
"coopGamePort": 36743
```

Firewall: allow inbound TCP 36743–36744 on loopback / Private profile (see Windows rules above). On Linux, loopback usually needs no extra rules.

### Host steps

1. Launch Ascendant with the **host** user dir; Load/Continue a save.
2. Adventure menu → **Host** → note the **8-character session code**.
3. Wait until the Hosting screen shows listening (overworld 36744).

### Guest steps

1. Launch Ascendant with the **guest** user dir; Load/Continue a **different** character.
2. Adventure menu → **Join** → address `127.0.0.1` (or `localhost`), paste the session code.
3. Confirm the guest enters the session world (normal save slots untouched).

### What to look for (CO3)

1. **Party / proximity:** with CO2 merged and both in party within radius, host colliding with an enemy opens a **Join the fight?** prompt on the guest (keyboard/mouse/controller). Timeout or Decline → host solos; Accept → co-op duel.
2. **Without party (or CO2 not merged):** no join prompt; host fights solo as usual. Stock online play and solo fights unchanged.
3. **Game port:** when the guest Accepts, host starts **36743**, guest LoginEvent carries the session code + normalised username, lobby slot 1 gets a remote GUI. If the guest never connects, host aborts to solo (never gives the guest seat to the host MatchController).
4. **During the duel:** both players control their own seats; enemy life/hand scaled for 2 humans.
5. **Guest drop:** kill the guest process mid-duel — host continues; guest seat concedes; host world stays playable.
6. **Results:** one match-outcome message (winner team, duel id, enemy id) when the **match** ends (not each game of a best-of-3). Each side runs its local loot / XP / removeEnemy / penalty path; host never applies guest-supplied reward numbers.
7. **Session end:** Stop on the host (or disconnect) clears the game port and restores guest save isolation.
