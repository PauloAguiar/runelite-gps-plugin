# GPS

Turn-by-turn navigation for Gielinor. Set a destination and GPS draws the fastest route, lists the steps to follow, tracks your progress live with an ETA, and tells you when you've arrived — plus alternative routes using different teleports, so you can pick the one that suits what you actually carry.

![GPS directions](docs/screenshots/gps-directions.png)

Built on [Shortest Path](https://github.com/Skretzo/shortest-path): the classic shortest-path behaviour is all still here, with a navigation layer and an alternative-routes explorer on top.

## Features

- **Turn-by-turn directions** — a movable overlay lists the route as steps: walking legs, doors to open, and the transports to use (teleports, boats, minecarts, balloons, gnome gliders, and more), with bank detours spelling out what to withdraw and counted in the ETA. Live progress with a per-step ETA, including mid-ride on carpets, canoes and gliders.
- **Alternative routes** — the side panel (blue pin icon) lists routes to the destination, each using a *different* travel method, ranked by travel time. Press **+** for more (the header and controls stay pinned as the cards scroll). Click a card to draw that route everywhere.
- **Travel methods catalog** — every method, categorised and searchable, with per-method include/exclude toggles that persist and availability markers that say exactly what's missing ("Requires 40 Firemaking", "Missing item: Willow logs", in your bank, quest not done). Seasonal (Leagues) methods are listed but disabled by default.
- **Travel options** — one panel section for the facts GPS can't read off the map, so routes match what you can actually use: your **house** contents (auto-detected while you're inside, and editable by hand), a **wilderness** avoid/allow toggle, balloon **Log storage** (tracked from chat so flights can be paid from storage), and which farmable **spirit trees** you've grown (read from the travel menu).
- **Availability modes** — route with only what you carry, what you carry plus your bank (routes detour to withdraw), everything you've unlocked, or every teleport in the game.
- **Destination search** — type a place, dungeon or minigame — or plain **coordinates** like `3221, 3218` — and pick a result; or use **Find nearest…** for the closest bank, altar, anvil, fairy ring and more, including **Bank (and back)**, a round trip ranked by the combined out-and-back time. Save any spot as a **favourite** (♥) and recall it by label.
- **Integration** — picks up destinations set by [Quest Helper](https://github.com/Zoinkwiz/quest-helper) and any plugin using the `gps` plugin-message API (the legacy `shortestpath` namespace is accepted too, so existing integrations keep working unchanged).

![Alternative routes panel](docs/screenshots/panel.png)

## Getting started

1. Set a destination: **right click** a spot on the world map, or **shift + right click** a tile in the scene — or search for a place (or coordinates) in the panel, or let Quest Helper set one.
2. Follow the directions overlay; drag it wherever you like.
3. For alternatives, open the side panel (blue pin icon) and click a route to travel it instead.

## Availability modes

| Family | Variant | What it considers |
|:--|:--|:--|
| **Owned** | Inventory | Only methods whose items you carry (inventory + equipment) |
| **Owned** | Inv + bank | Also items in your bank — routes walk to a bank to withdraw them |
| **All** | Available | Ignores item possession, but only methods your character has unlocked |
| **All** | Everything | Every teleport in the game, including ones you can't use yet |

## Feedback & community

- **[Discord](https://discord.gg/7VAbrPsUzT)** — questions, suggestions, or just to say a route surprised you. Also linked from the panel header.
- **Report an issue** — the red button in the panel header opens a GitHub issue pre-filled with your current routes, mode and settings. Calculate the misbehaving route first so the report captures it; for a full reproduction, attach a debug snapshot (⋯ menu → *Save debug snapshot*, saved to `.runelite/gps-debug/`).

## Credits

GPS is built on **[Shortest Path](https://github.com/Skretzo/shortest-path)** by Runemoro, Skretzo, FIrgolitsch, wvanderp and contributors, used under the BSD 2-Clause licence. The pathfinding engine, collision data and transport data come from that project; GPS adds the turn-by-turn navigation (directions, progress, ETA, arrival), the alternative-routes explorer, the travel-methods catalog with availability modes, the travel-options declarations (house, balloon and spirit-tree auto-detection), the destination search with coordinates and favourites, the door registry and hints, and the route rendering (arrow line, flowing glow, pulses and markers).

## License

BSD 2-Clause — see [LICENSE](LICENSE).
