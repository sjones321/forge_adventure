# forge-ascendant (Ascendant-Next)

Stage-1 walkable home plane for Shandalar Ascendant Next. See `docs/Ascendant-Next/World-Playable-Path.md`.

## Run

```bash
mvn -B -pl forge-ascendant -am package
./play-ascendant-next.sh          # Linux/mac
play-ascendant-next.cmd           # Windows
```

Or:

```bash
java -jar forge-ascendant/target/forge-ascendant-*-jar-with-dependencies.jar \
  --res forge-gui/res/adventure/common/maps --seed 1
```

## Controls

- **WASD / arrows** — move
- **M** — full map (fog of war on unexplored chunks)
- **F5 / F9** — save / load (`~/.forge-ascendant-next/slot1.json`)
- **Esc** — close map or quit

## What you should see

- Havenbrook stamped at the centre (Ninja TMX)
- Road to the approved **Plains Market** town
- Biome wedges, coast, trees/rocks, rivers
- Minimap + seed/position HUD

## Not yet

Fight, gather, interiors, dig, Forge app-shell mode entry. Those follow the playable path doc.
