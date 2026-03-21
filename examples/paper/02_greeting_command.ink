// Greeting Command Plugin
// A simple /greet command that greets the player

fn greet(playerName, greeting = "Hello") {
    return "${greeting}, ${playerName}! Welcome to our server."
}

// Command handlers would be registered via the plugin
// This is what the implementation would call:
let message = greet("Steve", "Hi")
print(message)

// With default:
let defaultMessage = greet("Alex")
print(defaultMessage)
