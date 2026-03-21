// Config Manager Plugin
// Shows how to use io for reading/writing config files

class Config {
    fn init(path) {
        self.path = path
        self.data = null
    }

    fn load() {
        let content = io.read(self.path)
        if content != "" {
            self.data = json.parse(content)
            print("Loaded config from ${self.path}")
        } else {
            print("Config file empty or not found, using defaults")
            self.data = {}
        }
    }

    fn save() {
        let content = json.stringify(self.data)
        io.write(self.path, content)
        print("Saved config to ${self.path}")
    }

    fn get(key, default = null) {
        if self.data != null && self.data has key {
            return self.data[key]
        }
        return default
    }

    fn set(key, value) {
        self.data[key] = value
    }
}

// Usage:
let config = Config("plugins/myplugin/config.json")
config.load()

let prefix = config.get("prefix", "&6[MyPlugin]&r")
let debug = config.get("debug", false)

print("Prefix: ${prefix}")
print("Debug mode: ${debug}")

// Update config
config.set("prefix", "&c[Updated]&r")
config.save()
