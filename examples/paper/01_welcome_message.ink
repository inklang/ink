// Welcome Message Plugin
// When a player joins, send them a welcome message

on player_join(player) {
    let playerName = player.name
    print("Welcome to the server, ${playerName}!")
    log("Player ${playerName} joined")
}
