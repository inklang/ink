# Homes Plugin - Design Spec

> Multi-home management for Inklang Minecraft server scripts.

## Overview

An Inklang plugin (`.ink` file in `plugins/ink/plugins/`) providing named home management for players. Players can save multiple named locations, delete them, list them, and teleport to them. Data is persisted to a SQLite database. Max homes per player and teleport behavior are configurable.

## Commands (stub)

Commands are handled by the command infrastructure being built separately. The Ink plugin exposes functions for that infrastructure to call:

| Function | Args | Returns |
|---|---|---|
| `__cmd_sethome(player, name)` | `Player`, `String` | `Result<String>` |
| `__cmd_home(player, name)` | `Player`, `String` | `Result<String>` |
| `__cmd_delhome(player, name)` | `Player`, `String` | `Result<String>` |
| `__cmd_listhomes(player)` | `Player` | `List<String>` |

Error return values are error description strings. Success returns vary by command.

## Configuration

`config.toml` in the plugin's data folder:

```toml
max_homes = 5
teleport_countdown_seconds = 0
```

- `max_homes` — Maximum homes a player can have. Default: `5`.
- `teleport_countdown_seconds` — Teleport delay in seconds. `0` = instant. Default: `0`.

## Data Model

### SQLite Table

```sql
CREATE TABLE IF NOT EXISTS homes (
    player_uuid TEXT NOT NULL,
    home_name TEXT NOT NULL,
    world TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, home_name)
);
```

### Home Class

```ink
class Home {
    fn init(name, world, x, y, z, created_at) {
        self.name = name
        self.world = world
        self.x = x
        self.y = y
        self.z = z
        self.created_at = created_at
    }
}
```

## Homes Module API

### `homes.setHome(player, name)`

Saves the player's current location as a named home.

- **Errors:** `"max_homes_reached"` — player already has `max_homes` homes.
- **Errors:** `"home_exists"` — player already has a home with this name.
- **Success:** Returns the `Home` object.

### `homes.getHome(player, name)`

Looks up a named home for a player.

- **Errors:** `"home_not_found"` — no home with that name.
- **Success:** Returns the `Home` object, or `null` if not found.

### `homes.deleteHome(player, name)`

Deletes a player's named home.

- **Errors:** `"home_not_found"` — no home with that name.
- **Success:** Returns `true`.

### `homes.listHomes(player)`

Lists all homes for a player.

- **Success:** Returns a `List<Home>`.

### `homes.teleportHome(player, name)`

Teleports the player to their named home.

- **Errors:** `"home_not_found"` — no home with that name.
- **Errors:** `"world_not_found"` — the home's world doesn't exist on the server.
- **Success:** Returns `"teleporting"` or `"countdown_started"` if using countdown mode.
- **Behavior:** If `teleport_countdown_seconds > 0`, the player is messaged with a countdown before teleporting. The `player.teleport()` call is made after the delay.

## Teleport Countdown Flow

1. `teleportHome` is called.
2. If `teleport_countdown_seconds == 0`: call `player.teleport(x, y, z, world)` immediately, return `"teleporting"`.
3. If `teleport_countdown_seconds > 0`: send `"Teleporting to {name} in {n} seconds..."` as action bar message. After `n` seconds, teleport and send `"Teleported to {name}!"`. Return `"countdown_started"`.

## Error Handling

All functions return error description strings on failure. Callers should check if the return value is an error string or a valid result.

## File Structure

```
plugins/ink/plugins/
  homes.ink          # The Ink plugin
```

The Ink plugin uses `io.readConfig("homes", "max_homes")` to read config values.

## Implementation Notes

- Uses `db` (SQLite) for persistence, accessed via `InkDb` interface.
- Home lookups use `db.query` with parameterized queries to avoid injection.
- Player UUID is stored as string from `player.name` (existing pattern in codebase).
- The `homes` module is exposed as a global object with methods via Ink's class/native function pattern.
