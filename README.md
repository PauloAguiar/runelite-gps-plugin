# GPS

Turn-by-turn navigation for Gielinor. Set a destination and GPS draws the fastest route, lists the steps to follow, tracks your progress live with an ETA, and tells you when you've arrived — plus alternative routes using different teleports, so you can pick the one that suits what you actually carry.

![GPS directions](docs/screenshots/gps-directions.png)

Built on [Shortest Path](https://github.com/Skretzo/shortest-path): the classic shortest-path behaviour is all still here, with a navigation layer and an alternative-routes explorer on top.

## Features

- **Turn-by-turn directions** — a movable overlay lists the route as steps: walking legs, doors to open, teleports and transports to use, bank detours with what to withdraw. Live progress with a per-step ETA, including mid-ride on carpets, canoes and gliders.
- **Alternative routes** — the side panel (blue pin icon) lists routes to the destination, each using a *different* travel method, ranked by travel time. Click a card to draw that route everywhere.
- **Teleport-method catalog** — every method, categorised and searchable, with per-method include/exclude toggles that persist and availability markers (missing item, in your bank, level too low, quest not done). Seasonal (Leagues) methods are listed but disabled by default.
- **Availability modes** — route with only what you carry, what you carry plus your bank (routes detour to withdraw), everything you've unlocked, or every teleport in the game.
- **Destination search** — type a place, dungeon or minigame in the panel and pick a result; or use **Find nearest…** for the closest bank, altar, anvil, fairy ring and more — including **Bank (and back)**, a round trip ranked by the combined out-and-back time.
- **Integration** — picks up destinations set by [Quest Helper](https://github.com/Zoinkwiz/quest-helper) and any plugin using the `gps` plugin-message API (the legacy `shortestpath` namespace is accepted too, so existing integrations keep working unchanged).

![Alternative routes panel](docs/screenshots/panel.png)

## Getting started

1. Set a destination: **right click** a spot on the world map, or **shift + right click** a tile in the scene — or let Quest Helper set one.
2. Follow the directions overlay; drag it wherever you like.
3. For alternatives, open the side panel (blue pin icon) and click a route to travel it instead.

## Availability modes

| Family | Variant | What it considers |
|:--|:--|:--|
| **Owned** | Inventory | Only methods whose items you carry (inventory + equipment) |
| **Owned** | Inv + bank | Also items in your bank — routes walk to a bank to withdraw them |
| **All** | Available | Ignores item possession, but only methods your character has unlocked |
| **All** | Everything | Every teleport in the game, including ones you can't use yet |

## Credits

GPS is built on **[Shortest Path](https://github.com/Skretzo/shortest-path)** by Runemoro, Skretzo, FIrgolitsch, wvanderp and contributors, used under the BSD 2-Clause licence. The pathfinding engine, collision data and transport data come from that project; GPS adds the turn-by-turn navigation (directions, progress, ETA, arrival), the alternative-routes explorer, the method catalog with availability modes, the destination search, the door registry and hints, and the route rendering (arrow line, flowing glow, pulses and markers).

## License

BSD 2-Clause — see [LICENSE](LICENSE).
