# ink.mobs Library — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `ink.mobs` as a proper declarative mob behavior library for inklang, inspired by MythicMobs. Ships as a grammar extension + runtime built-ins.

**Architecture:**
- Grammar extension adds `mob` keyword with flat meta fields, `equipment {}`, `drops {}`, `experience`, and `skills {}` with threshold triggers
- Kotlin `MobHandler`/`MobListener` extended to parse new CST fields, apply equipment on spawn, handle drops/XP on death, evaluate threshold conditions
- Skill functions (`particle_effect`, `damage`, `teleport`, etc.) registered as `Value.NativeFunction` built-ins via `BukkitRuntimeRegistrar` — called from Ink code just like `print()`
- Printing Press compiles skill calls in `.ink` → `CALL` opcodes that resolve to the registered built-ins

**Tech Stack:** Kotlin (VM/runtime), TypeScript/Node (grammar IR build), Rust (Printing Press compiler — unchanged), PaperMC API

---

## Chunk 1: Update Grammar Extension

**Files:**
- Modify: `examples/ink.mobs/src/grammar.ts`
- Modify: `examples/ink.mobs/ink-package.toml` (version bump)
- Build output: `examples/ink.mobs/dist/grammar.ir.json`

### Task 1.1: Rewrite grammar.ts

- [ ] **Step 1: Rewrite grammar.ts**

```typescript
import { defineGrammar, declaration, rule, seq, choice, zeroOrMore, oneOrMore, keyword, identifier, colon, block, expression, optional } from '@inklang/quill/grammar'

export default defineGrammar({
  package: 'ink.mobs',
  declarations: [
    declaration({
      keyword: 'mob',
      inheritsBase: true,
      rules: [
        // Flat meta fields: name: "value", health: 200, damage: 8, etc.
        rule('meta_field', r => r.seq(identifier(), colon(), expression())),

        // equipment { head: DIAMOND_HELMET, main: IRON_SWORD, ... }
        rule('equipment_slot', r => r.seq(
          choice([
            r.keyword('head'), r.keyword('main'), r.keyword('offhand'),
            r.keyword('legs'), r.keyword('chest'), r.keyword('boots'),
            r.keyword('helmet'), r.keyword('chestplate'), r.keyword('leggings')
          ]),
          colon(), expression()
        )),
        rule('equipment_block', r => r.seq(
          keyword('equipment'), block(r.oneOrMore(r.equipment_slot()))
        )),

        // drops { IRON_INGOT 50%, DIAMOND 5% 1, BONE 100% 2-4 }
        rule('drop_entry', r => r.seq(
          expression(),                              // item
          expression().optional(),                   // chance %
          keyword('amount').optional(expression())   // amount
        )),
        rule('drops_block', r => r.seq(
          keyword('drops'), block(r.oneOrMore(r.drop_entry()))
        )),

        // experience: 50
        rule('experience_field', r => r.seq(keyword('experience'), colon(), expression())),

        // skills { on_spawn { ... }, on_damaged >50% { ... } }
        rule('trigger_threshold', r => r.seq(
          choice([r.string('>'), r.string('<'), r.string('=')]),
          expression()
        )),
        rule('skill_event', r => r.choice(
          r.seq(keyword('on_spawn'),      r.trigger_threshold().optional()),
          r.seq(keyword('on_death'),      r.trigger_threshold().optional()),
          r.seq(keyword('on_damage'),     r.trigger_threshold().optional()),
          r.seq(keyword('on_damaged'),    r.trigger_threshold()),
          r.seq(keyword('on_explode'),    r.trigger_threshold().optional()),
          r.seq(keyword('on_target'),     r.trigger_threshold().optional()),
          r.seq(keyword('on_interact'),   r.trigger_threshold().optional()),
          r.seq(keyword('on_enter_combat'),  r.trigger_threshold().optional()),
          r.seq(keyword('on_leave_combat'),  r.trigger_threshold().optional()),
          r.seq(keyword('on_tick'), r.keyword('every').optional(), expression())
        )),
        rule('skill_call', r => r.seq(
          identifier(),
          r.zeroOrMore(expression())
        )),
        rule('skills_block', r => r.seq(
          keyword('skills'),
          block(r.zeroOrMore(r.seq(r.skill_event(), block(r.oneOrMore(r.skill_call())))))
        )),
      ]
    })
  ]
})
```

- [ ] **Step 2: Build grammar IR**
  Run: `cd examples/ink.mobs && npm run build` (or the quill grammar build command — check `package.json`)
  Expected: `dist/grammar.ir.json` updated with new rules

- [ ] **Step 3: Commit**
  ```bash
  git add examples/ink.mobs/src/grammar.ts examples/ink.mobs/dist/grammar.ir.json
  git commit -m "feat(mobs): expand grammar with equipment, drops, experience, skills blocks, threshold triggers"
  ```

---

## Chunk 2: Extend Kotlin MobHandler and MobListener

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobHandler.kt`
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobListener.kt`
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/EntityClass.kt` (if needed for equipment)
- Test: existing mob tests + manual testing in Paper server

### Task 2.1: Extend MobHandler to extract new CST fields

- [ ] **Step 1: Read current MobHandler**

MobHandler currently extracts only event handler funcIdx. Need to also extract:
- `equipment` map (slot → item expression)
- `drops` list (item, chance, amount range)
- `experience` value
- `threshold` per skill event (for `on_damaged >50%` etc.)

- [ ] **Step 2: Modify `extractHandlers` to also extract metadata**

Add a new data class `MobMetadata` and extract it alongside handlers:

```kotlin
data class MobMetadata(
    val entityType: EntityType,
    val name: String,
    val equipment: Map<String, Value> = emptyMap(),       // slot -> ItemStack value
    val drops: List<DropEntry> = emptyList(),
    val experience: Int = 0,
    val handlers: Map<String, Int> = emptyMap(),          // eventName -> funcIdx
    val thresholds: Map<String, Threshold> = emptyMap()   // eventName -> threshold
)

data class DropEntry(
    val item: String,         // material name e.g. "IRON_INGOT"
    val chance: Double,       // 0.0 to 1.0
    val amountMin: Int = 1,
    val amountMax: Int = 1
)

data class Threshold(
    val op: String,   // ">", "<", "="
    val value: Double
)
```

- [ ] **Step 3: Modify `extractHandlers` function**

Replace the current `extractHandlers` with one that also walks meta_field, equipment_block, drops_block, experience_field, and skills_block nodes. For each skill_event, extract the threshold if present.

Key rule names to match (prefixed with `ink.mobs/` in the IR):
- `meta_field` — identifier colon expression
- `equipment_block` — keyword('equipment') + block with equipment_slot children
- `drops_block` — keyword('drops') + block with drop_entry children
- `experience_field` — keyword('experience') colon expression
- `skills_block` — keyword('skills') + block with skill_event children
- `trigger_threshold` — ('>' | '<' | '=') expression

For drop entries, parse chance as percentage (e.g. `50%` → 0.5). If no chance, default to 100%. If no amount, default to 1.

- [ ] **Step 4: Pass metadata to MobListener**

Update `MobListener` constructor to receive `MobMetadata` and use it:
- Apply equipment on spawn
- Handle drops + XP on death
- Check thresholds before executing skill

- [ ] **Step 5: Commit**
  ```bash
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobHandler.kt
  git commit -m "feat(mobs): extend MobHandler to extract equipment, drops, experience, thresholds from CST"
  ```

### Task 2.2: Extend MobListener for equipment, drops, XP, and threshold triggers

- [ ] **Step 1: Modify MobListener to receive MobMetadata**

```kotlin
class MobListener(
    private val entityType: EntityType,
    private val mobName: String,
    private val metadata: MobMetadata,   // changed: replaced handlers with full metadata
    private val chunk: Chunk,
    private val vm: ContextVM,
    private val server: Server
) : Listener {
```

- [ ] **Step 2: Add equipment application in onEntitySpawn**

On spawn, iterate `metadata.equipment` and call `entity.setEquipment(slot, itemStack)`. Resolve item material names to Bukkit `ItemStack` via `Bukkit.getItemFactory()`.

- [ ] **Step 3: Add drops + XP handling in onEntityDeath**

1. Set `evt.dropExp(metadata.experience)` (or equivalent)
2. For each `DropEntry` in `metadata.drops`: roll `Math.random() < chance`; if true, compute random amount in range `[amountMin, amountMax]` and `evt.getDrops().add(new ItemStack(material, amount))`

- [ ] **Step 4: Implement threshold checks in each event handler**

For events with a threshold (`on_damaged >50%`, etc.), evaluate the threshold BEFORE executing the skill:
- `on_damaged >50%`: `entity.health / entity.maxHealth < 0.50` — note health is AFTER damage applied
- `on_damaged <25%`: `entity.health / entity.maxHealth < 0.25`
- `on_damaged =100%`: always true (fires every time)

If threshold not met, skip skill execution.

- [ ] **Step 5: Add new event listeners for additional events**

Register listeners for:
- `EntityExplodeEvent` → `on_explode`
- `EntityEnterCombatEvent` → `on_enter_combat` (API 1.21+)
- `EntityExitCombatEvent` → `on_leave_combat`

- [ ] **Step 6: Run build to verify compilation**
  Run: `./gradlew :ink-bukkit:compileKotlin`
  Expected: Compiles without errors

- [ ] **Step 7: Commit**
  ```bash
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobListener.kt
  git commit -m "feat(mobs): extend MobListener for equipment, drops, XP, threshold triggers, additional events"
  ```

---

## Chunk 3: Register Skill Built-ins

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrar.kt`
- Test: Write simple mob test that uses skills

### Task 3.1: Register all skill functions as NativeFunctions

- [ ] **Step 1: Read BukkitRuntimeRegistrar**

```kotlin
// BukkitRuntimeRegistrar.kt — registers built-in functions/classes
object BukkitRuntimeRegistrar {
    fun register(sender: CommandSender, server: Server) {
        // registers Player, World, Location, Entity, Server, Inventory classes
    }
}
```

- [ ] **Step 2: Add a new MobSkills class with skill implementations**

Create `ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/MobSkills.kt`:

Each skill is a `Value.NativeFunction` that reads its args and performs Bukkit API actions.

```kotlin
object MobSkills {
    // particle_effect "happyVillager" 10
    val PARTICLE_EFFECT = Value.NativeFunction { args ->
        val effect = expectString(args.getOrNull(0) ?: return@NativeFunction error("particle_effect: missing effect name"))
        val count = expectInt(args.getOrNull(1) ?: return@NativeFunction error("particle_effect: missing count"))
        // ParticleEffect from Bukkit — get from server
        // Player (if entity is player) or world.spawnParticle
        Value.Null
    }

    val SOUND = Value.NativeFunction { args ->
        val sound = expectString(args.getOrNull(0) ?: return@NativeFunction error("sound: missing sound id"))
        val volume = toDouble(args.getOrNull(1) ?: Value.Double(1.0))
        // entity.world.playSound(entity.location, sound, volume, 1.0f)
        Value.Null
    }

    val EXPLOSION = Value.NativeFunction { args ->
        val radius = toDouble(args.getOrNull(0) ?: Value.Double(1.0))
        // entity.world.createExplosion(entity.location, radius.toFloat(), false, true)
        Value.Null
    }

    val MESSAGE = Value.NativeFunction { args ->
        val text = args.map { toString(it) }.joinToString("")
        // entity.world.getPlayers().forEach { it.sendMessage(text) }
        Value.Null
    }

    val DAMAGE = Value.NativeFunction { args ->
        val amount = toDouble(args.getOrNull(0) ?: Value.Double(1.0))
        // entity.attack(target, amount) — or damage target
        Value.Null
    }

    val HEAL = Value.NativeFunction { args ->
        val amount = toDouble(args.getOrNull(0) ?: Value.Double(1.0))
        // entity.health = min(entity.maxHealth, entity.health + amount)
        Value.Null
    }

    val APPLY_EFFECT = Value.NativeFunction { args ->
        val effect = expectString(args.getOrNull(0) ?: return@NativeFunction error("apply_effect: missing effect name"))
        val seconds = expectInt(args.getOrNull(1) ?: return@NativeFunction error("apply_effect: missing duration"))
        // PotionEffectType.valueOf(effect.uppercase())...
        Value.Null
    }

    val SPEED_BOOST = Value.NativeFunction { args ->
        val level = expectInt(args.getOrNull(0) ?: return@NativeFunction error("speed_boost: missing level"))
        val seconds = expectInt(args.getOrNull(1) ?: return@NativeFunction error("speed_boost: missing duration"))
        // apply PotionEffectType.SPEED
        Value.Null
    }

    val JUMP_BOOST = Value.NativeFunction { args ->
        val level = expectInt(args.getOrNull(0) ?: return@NativeFunction error("jump_boost: missing level"))
        val seconds = expectInt(args.getOrNull(1) ?: return@NativeFunction error("jump_boost: missing duration"))
        // apply PotionEffectType.JUMP
        Value.Null
    }

    val TELEPORT = Value.NativeFunction { args ->
        val x = toDouble(args.getOrNull(0) ?: return@NativeFunction error("teleport: missing x"))
        val y = toDouble(args.getOrNull(1) ?: return@NativeFunction error("teleport: missing y"))
        val z = toDouble(args.getOrNull(2) ?: return@NativeFunction error("teleport: missing z"))
        // entity.teleport(new Location(entity.world, x, y, z))
        Value.Null
    }

    val SUMMON = Value.NativeFunction { args ->
        val entityType = expectString(args.getOrNull(0) ?: return@NativeFunction error("summon: missing entity type"))
        val x = toDouble(args.getOrNull(1) ?: Value.Double(0.0))
        val y = toDouble(args.getOrNull(2) ?: Value.Double(0.0))
        val z = toDouble(args.getOrNull(3) ?: Value.Double(0.0))
        // entity.world.spawnEntity(new Location(...), EntityType.valueOf(entityType.uppercase()))
        Value.Null
    }

    val REMOVE_ENTITY = Value.NativeFunction { args ->
        // entity.remove()
        Value.Null
    }

    val ALL = mapOf(
        "particle_effect" to PARTICLE_EFFECT,
        "sound" to SOUND,
        "explosion" to EXPLOSION,
        "message" to MESSAGE,
        "damage" to DAMAGE,
        "heal" to HEAL,
        "apply_effect" to APPLY_EFFECT,
        "speed_boost" to SPEED_BOOST,
        "jump_boost" to JUMP_BOOST,
        "teleport" to TELEPORT,
        "summon" to SUMMON,
        "remove" to REMOVE_ENTITY
    )
}
```

Note on `teleport` and `summon`: use `~` prefix to indicate relative offset. Parse `~0 ~1 ~0` as `entity.location + offset`.

Note on `damage`: needs access to `target` from the skill context. The `entity` global is always available. `target` may be null.

- [ ] **Step 3: Register skills in BukkitRuntimeRegistrar**

```kotlin
// In BukkitRuntimeRegistrar.register():
// After existing class registrations:
MobSkills.ALL.forEach { (name, fn) ->
    // register as global function
}
```

- [ ] **Step 4: Register `if` as a built-in native function**

The `if` skill needs special handling since it takes an Ink expression (condition) and two skill lists. In practice, `if` is compiled by Printing Press to normal control flow in the bytecode, so it may not need a native function at all — the compiler lowers it. Verify this assumption with a test ink script that uses `if`.

- [ ] **Step 5: Test the skills by running `./gradlew :ink-bukkit:test`**

Expected: Tests compile and run (may have pre-existing failures from `has` operator issue — focus on compilation)

- [ ] **Step 6: Commit**
  ```bash
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/MobSkills.kt
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrar.kt
  git commit -m "feat(mobs): add MobSkills built-in functions (particle, sound, explosion, damage, heal, etc.)"
  ```

---

## Chunk 4: Write Example Mob Scripts

**Files:**
- Modify: `examples/ink.mobs/scripts/zombie.ink`
- Modify: `examples/ink.mobs/scripts/creeper.ink`
- Modify: `examples/ink.mobs/scripts/boss.ink`
- Create: `examples/ink.mobs/scripts/iron_golem.ink`
- Modify: `examples/ink.mobs/scripts/main.ink`
- Build: Compile each script with Printing Press

### Task 4.1: Write comprehensive example mobs

- [ ] **Step 1: Rewrite zombie.ink**

```ink
// zombie.ink — standard hostile mob with equipment, drops, and skills

mob Zombie {
  name: "§cUndead Warrior"
  health: 200
  damage: 8
  tier: 2

  equipment {
    head: DIAMOND_HELMET
    main: IRON_SWORD
    boots: IRON_BOOTS
  }

  drops {
    IRON_INGOT 50%
    BONE 100% 2-4
    DIAMOND 5% 1
  }

  experience: 50

  skills {
    on_spawn {
      particle_effect "happyVillager" 5
      message "§6A zombie warrior rises!"
    }

    on_death {
      sound "entity.zombie.death" 1.0
      explosion 1
    }

    on_damaged >50% {
      speed_boost 1 5s
      message "§cThe zombie is wounded!"
    }
  }
}
```

- [ ] **Step 2: Rewrite creeper.ink**

```ink
// creeper.ink — creeper with explosion on death

mob Creeper {
  name: "§aCharged Creeper"
  health: 100
  damage: 6
  tier: 2

  drops {
    SULPHUR 100% 1-3
    DIAMOND 2% 1
  }

  experience: 30

  skills {
    on_spawn {
      particle_effect "note" 3
    }

    on_explode {
      explosion 5
      summon "firework_rocket" ~0 ~1 ~0
    }

    on_death {
      explosion 3
    }
  }
}
```

- [ ] **Step 3: Rewrite boss.ink**

```ink
// boss.ink — tier 5 boss with multiple phases and abilities

mob Wither {
  name: "§4§lThe Wither"
  health: 5000
  damage: 15
  tier: 5

  drops {
    NETHER_STAR 100% 1
    NETHERITE_INGOT 50% 1-2
    DIAMOND 100% 3-5
  }

  experience: 1000

  skills {
    on_spawn {
      message "§c§lTHE WITHER HAS AWAKENED"
      sound "entity.wither.spawn" 2.0
      particle_effect "smoke" 50
    }

    on_damaged >75% {
      speed_boost 2 10s
      message "§cThe Wither is enraged! §7[Phase 1]"
    }

    on_damaged >50% {
      speed_boost 3 15s
      message "§4The Wither is furious! §7[Phase 2]"
      particle_effect "lava" 20
    }

    on_damaged >25% {
      explosion 4
      summon "wither_skull" ~1 ~1 ~0
      message "§4§lFINAL PHASE — The Wither unleashes its fury!"
    }

    on_death {
      message "§a§lThe Wither has been defeated!"
      explosion 10
      summon "firework_rocket" ~0 ~5 ~0
      sound "entity.wither.death" 2.0
    }
  }
}

mob EnderDragon {
  name: "§5§lEnder Dragon"
  health: 3000
  damage: 12
  tier: 5

  drops {
    DRAGON_BREATH 100% 2-4
    ENDER_PEARL 100% 5-10
    ELYTRA 20% 1
  }

  experience: 2000

  skills {
    on_spawn {
      message "§5The Ender Dragon stirs..."
      particle_effect "portal" 30
    }

    on_damaged >50% {
      speed_boost 2 10s
      message "§dThe dragon takes flight!"
    }

    on_death {
      message "§a§lThe dragon is slain! The End is free."
      explosion 8
    }
  }
}
```

- [ ] **Step 4: Create iron_golem.ink**

```ink
// iron_golem.ink — passive/utility mob

mob IronGolem {
  name: "§fIron Defender"
  health: 400
  damage: 12
  tier: 3

  equipment {
    head: IRON_HELMET
    chest: DIAMOND_CHESTPLATE
    main: IRON_SWORD
  }

  drops {
    IRON_INGOT 100% 2-5
    POPPY 30% 1
  }

  experience: 200

  skills {
    on_spawn {
      message "§fAn iron defender awakens."
    }

    on_damaged >30% {
      speed_boost 1 5s
      message "§eThe golem defends its territory!"
    }

    on_death {
      explosion 2
    }
  }
}
```

- [ ] **Step 5: Compile scripts with Printing Press**

Run: `cd ~/dev/printing_press && cargo build --release`
Then: `./target/release/printing_press compile examples/ink.mobs/scripts/zombie.ink -o examples/ink.mobs/dist/scripts/zombie.inkc` (repeat for each script)

- [ ] **Step 6: Commit examples**
  ```bash
  git add examples/ink.mobs/scripts/
  git commit -m "feat(mobs): rewrite example scripts with full grammar (equipment, drops, skills)"
  ```

---

## Chunk 5: Update ink-manifest.json for Lectern

**Files:**
- Modify: `examples/ink.mobs/dist/ink-manifest.json`

- [ ] **Step 1: Update ink-manifest.json**

```json
{
  "name": "ink.mobs",
  "version": "0.1.0",
  "description": "Declarative mob behavior library for inklang — inspired by MythicMobs. Define custom mobs with metadata, equipment, drops, and event-driven skills.",
  "grammar": "grammar.ir.json",
  "scripts": [
    "boss.inkc",
    "creeper.inkc",
    "iron_golem.inkc",
    "main.inkc",
    "zombie.inkc"
  ],
  "keywords": ["mobs", "gaming", "paper", "minecraft", "mcpe", "server"],
  "author": "<your-handle>",
  "license": "MIT"
}
```

- [ ] **Step 2: Commit**
  ```bash
  git add examples/ink.mobs/dist/ink-manifest.json
  git commit -m "chore(mobs): update ink-manifest for lectern publishing"
  ```

---

## Chunk 6: Manual Integration Test

**Test in Paper server:**

- [ ] **Step 1: Build ink-bukkit**

Run: `./gradlew :ink-bukkit:build`
Expected: JAR built successfully

- [ ] **Step 2: Copy JAR to Paper server and start**

- [ ] **Step 3: Write a simple test mob script**

In a running server, create `plugins/ink/plugins/test_mobs/main.ink`:
```ink
mob Pig {
  name: "§dTest Pig"
  health: 50
  skills {
    on_spawn {
      message "A test pig spawned!"
      particle_effect "heart" 5
    }
  }
}
```

- [ ] **Step 4: Verify mob loads and skills fire**
- Spawn a pig → should see message + particles
- Kill the pig → should see death effects
- Check server log for any errors

---

## File Summary

| File | Action |
|------|--------|
| `examples/ink.mobs/src/grammar.ts` | Rewrite — new rules |
| `examples/ink.mobs/dist/grammar.ir.json` | Rebuild |
| `examples/ink.mobs/ink-package.toml` | Version bump |
| `examples/ink.mobs/scripts/zombie.ink` | Rewrite |
| `examples/ink.mobs/scripts/creeper.ink` | Rewrite |
| `examples/ink.mobs/scripts/boss.ink` | Rewrite |
| `examples/ink.mobs/scripts/iron_golem.ink` | Create |
| `examples/ink.mobs/scripts/main.ink` | Update |
| `examples/ink.mobs/dist/scripts/*.inkc` | Rebuild |
| `examples/ink.mobs/dist/ink-manifest.json` | Update |
| `ink-bukkit/.../handlers/MobHandler.kt` | Extend — parse new CST fields |
| `ink-bukkit/.../handlers/MobListener.kt` | Extend — equipment/drops/XP/thresholds |
| `ink-bukkit/.../runtime/MobSkills.kt` | Create — skill implementations |
| `ink-bukkit/.../runtime/BukkitRuntimeRegistrar.kt` | Register MobSkills |

## Deferred / Future

- [ ] `on_tick every N` — periodic task scheduling (needs scheduler integration)
- [ ] `random { skill1 50% skill2 50% }` — randomized skill selection
- [ ] `if` expression lowering — verify `if` compiles to bytecode correctly
- [ ] Scalable difficulty via `tier` multiplier
- [ ] Custom AI goals
- [ ] Phase system
- [ ] Publish to lectern registry
