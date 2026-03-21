# Ink Paper Plugin Examples

Simple plugins demonstrating the ink scripting language for PaperMC servers.

## Language Basics

```ink
// Variables
let x = 10
let name = "Steve"

// Functions with default params
fn greet(name, greeting = "Hello") {
    return "${greeting}, ${name}!"
}

// Classes
class Point {
    fn init(x, y) {
        self.x = x
        self.y = y
    }
}

// Arrays and Maps
let arr = [1, 2, 3]
let map = {"key": "value"}

// Loops
for i in 0..10 {
    print(i)
}

while x > 0 {
    x = x - 1
}
```

## Builtins

| Builtin | Description |
|---------|-------------|
| `print(msg)` | Send message to player/command sender |
| `log(msg)` | Log to server console |
| `io.read(path)` | Read file contents |
| `io.write(path, content)` | Write to file |
| `json.parse(str)` | Parse JSON string |
| `json.stringify(value)` | Convert to JSON |
| `db.from(table)` | Query database table |
| `db.registerTable(name, fields, keyIndex)` | Register a table schema |

## Events

Register event handlers with `on event_name(params) { }`:

```ink
on player_join(player) {
    print("Welcome, ${player.name}!")
}

on player_quit(player) {
    log("${player.name} left the server")
}

on block_break(block, player) {
    log("${player.name} broke ${block.type}")
}

on block_place(block, player) {
    log("${player.name} placed ${block.type}")
}
```

## Examples

| File | Description |
|------|-------------|
| `01_welcome_message.ink` | Basic player join event |
| `02_greeting_command.ink` | Function with default parameters |
| `03_block_break_counter.ink` | Tracking player stats with Map |
| `04_player_data.ink` | Database persistence |
| `05_config_manager.ink` | File-based config with io |
| `06_simple_teleport.ink` | Class-based warp system |

## Usage

Place `.ink` script files in your server's `plugins/ink/scripts/` directory.
The InkPlugin will automatically load and execute them.
