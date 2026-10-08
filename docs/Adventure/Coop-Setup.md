# Ascendant co-op setup (CO1)

Direct, no servers. One player **hosts**; the other **joins** over LAN or Tailscale.
Ascendant only — stock Forge / non-Ascendant Adventure worlds do not show Host / Join.

## Port

| Port  | Role |
|------:|------|
| **36744** | Ascendant co-op overworld session (handshake, world sync, CO2). |

Tunable in the Ascendant `config.json` as `coopOverworldPort`.

> The existing Forge duel port **36743** is reserved for co-op duels in **CO3** and is not used or required for CO1.

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
4. On disconnect your normal save is restored; your character file under `characters/` is updated.

## Windows firewall

Run these in an **Administrator** PowerShell on the **host** PC.

Scope rules to the Java/Forge program and to Tailscale or the local subnet — do not open 36744 to the whole Internet.

**Tailscale** (recommended):

```powershell
# Run as Administrator
New-NetFirewallRule -DisplayName "Forge Ascendant Co-op Overworld (Tailscale)" `
  -Direction Inbound -Protocol TCP -LocalPort 36744 -Action Allow -Profile Private `
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
```

Also confirm the **Tailscale adapter's network profile is Private** (Windows Settings → Network & Internet → the Tailscale connection → Private). A Public profile can still block inbound rules even when the rule exists.

## Tailscale vs LAN / UPnP

- **Tailscale** (`100.64.0.0/10`, treated as any `100.x` IPv4): both PCs on the same Tailnet; **do not use UPnP**. Join with the host's Tailscale IP and session code. Prefer binding the host to that `100.x` address via `coopBindAddress`.
- **LAN**: same subnet; use the LocalSubnet firewall rule above. UPnP is skipped by default for co-op.
- **WAN without Tailscale**: not supported for CO1.

## Version / session mismatch

Co-op uses a **hard** check (classic online only warns):

- Matching **8-character session code** (5 failed attempts from one address → 5-minute lockout; host stays **HOSTING**)
- Same Forge **build hash** (version + build timestamp)
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
Wire `PROTOCOL_VERSION = 5` (round 3: teleport move samples + reported max speed).

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

**CO3-stable hooks (unchanged):** `CoopHooks.notifyFightAboutToStart(String)`,
`CoopHooks.notifyGuestEnemyEncounter(long, String, String)` /
`GuestEnemyEncounterHandler`, and `CoopPartyState.inParty()` /
`withinRadius(...)`.

See `CoopHooks` / `CoopOverworldRuntime` and `docs/Adventure/Ascendant-Roadmap.md`.
