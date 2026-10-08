# Ascendant co-op setup (CO1)

Direct, no servers. One player **hosts**; the other **joins** over LAN or Tailscale.
Ascendant only — stock Forge / non-Ascendant Adventure worlds do not show Host / Join.

## Ports

| Port  | Role |
|------:|------|
| **36743** | Existing Forge game / duel lobby port. Reserved for CO3 co-op duels. |
| **36744** | New Ascendant overworld session port (handshake, world sync, CO2). |

Tunable in the Ascendant `config.json` as `coopGamePort` / `coopOverworldPort`.

## Host

1. Play **Shandalar Ascendant**, Load or Continue a save (the host's save owns the world).
2. From the Adventure main menu, tap **Host**.
3. Share one of the listed addresses with your guest (LAN IP or Tailscale `100.x`).
4. Default hosting **skips UPnP** (`coopSkipUPnP: true`). Use Tailscale, or open the ports manually.

## Join

1. Load or Continue **your** character on the guest PC (collection, decks, skills stay local).
2. Tap **Join** and enter the host address:
   - Tailscale: `100.x.y.z` (port defaults to 36744)
   - LAN: `192.168.x.x` or `192.168.x.x:36744`
3. On success the guest rebuilds the world from the host's seed and plane config, verifies a hash, and falls back to receiving the world blob if the hash differs.

## Windows firewall

On the **host** PC, allow inbound TCP for Forge on both ports (Private networks is enough for LAN/Tailscale):

```text
New-NetFirewallRule -DisplayName "Forge Ascendant Co-op Game" -Direction Inbound -Protocol TCP -LocalPort 36743 -Action Allow -Profile Private
New-NetFirewallRule -DisplayName "Forge Ascendant Co-op Overworld" -Direction Inbound -Protocol TCP -LocalPort 36744 -Action Allow -Profile Private
```

Or: Windows Security → Firewall → Advanced settings → Inbound Rules → New Rule → Port → TCP 36743,36744 → Allow.

## Tailscale vs LAN / UPnP

- **Tailscale** (`100.64.0.0/10`, treated as any `100.x` IPv4): both PCs on the same Tailnet; **do not use UPnP**. Join with the host's Tailscale IP.
- **LAN**: same subnet; open the firewall ports above. UPnP is skipped by default for co-op; enable only if you add a UPnP helper later.
- **WAN without Tailscale**: not recommended for CO1; forward both ports manually if you must.

## Version mismatch

Co-op uses a **hard** check (classic online only warns):

- Same Forge **build hash** (version + build timestamp)
- Same loaded **card data hash**

If either differs, the host sends `CoopHelloRejectEvent` and the guest disconnects. Update both installs (or regenerate card data) so they match, then try again.

## Character vs world

| | Host | Guest |
|---|---|---|
| World (map, enemies, POIs, nodes) | Owns / saves | Rebuilds from seed or receives blob; host remains authority |
| Character (collection, decks, skills, materials, items) | Local | Local — saved under `adventure/<plane>/characters/` when the session ends |

## CO2 / CO3

CO1 is session and connection only. See `CoopHooks` and `docs/Adventure/Ascendant-Roadmap.md` packages CO2–CO3 for shared overworld and co-op duels.
