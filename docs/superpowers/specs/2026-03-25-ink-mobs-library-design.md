# ink.mobs — Library Design Specification

> **Project:** inklang / ink.mobs package
> **Date:** 2026-03-25
> **Status:** Draft

## Overview

`ink.mobs` is a declarative mob behavior library for inklang, inspired by MythicMobs. It lets server admins define custom mob types with metadata, equipment, drops, and event-driven skills entirely in `.ink` source files — no Java/Kotlin knowledge required. The package ships as a grammar extension (adding the `mob` keyword) plus a runtime library that interprets skill declarations against the PaperMC/Bukkit API.

The package targets **PaperMC 1.21+** servers running the inklang VM plugin.

---

## Package Structure

```
ink.mobs/
├── ink-package.toml          # package manifest
├── src/
│   └── grammar.ts            # Grammar extension (mob keyword + clauses)
├── dist/
│   ├── grammar.ir.json       # compiled grammar IR
│   └── ink-manifest.json      # package metadata for lectern
├── lib/
│   ├── skills/
│   │   ├── index.ts           # skill registry + dispatch
│   │   ├── effects.ts         # particle, sound, explosion, message
│   │   ├── combat.ts          # damage, heal, apply_effect
│   │   ├── utility.ts         # teleport, summon, speed_boost, jump_boost
│   │   └── logic.ts           # if/else, threshold guards
│   ├── mechanics/
│   │   ├── index.ts
│   │   ├── equipment.ts       # equipment parsing + application
│   │   ├── drops.ts           # item drop tables
│   │   └── experience.ts      # XP configuration
│   └── runtime/
│       ├── MobHandler.ts      # grammar handler (Kotlin counterpart: MobHandler.kt)
│       ├── MobListener.ts     # event listener wiring
│       └── context.ts         # per-mob skill execution context
├── scripts/
│   ├── main.ink               # no-op entry (package load message)
│   ├── zombie.ink              # example: basic hostile
│   ├── creeper.ink             # example: timed explosion
│   └── boss.ink                # example: boss with phases
└── README.md
```

---

## Grammar

### Package Declaration

Packages declare their grammar extensions via `ink-package.toml`:

```toml
[package]
name = "ink.mobs"
version = "0.1.0"

[grammar]
entry = "src/grammar.ts"
output = "dist/grammar.ir.json"
```

### Grammar IR (`src/grammar.ts`)

```typescript
import { defineGrammar, declaration, rule } from '@inklang/quill/grammar'

export default defineGrammar({
  package: 'ink.mobs',
  declarations: [
    declaration({
      keyword: 'mob',
      inheritsBase: true,
      rules: [
        // Meta fields (flat key-value)
        rule('meta_field', r => r.seq(r.identifier(), r.colon(), r.expression())),

        // Nested equipment block
        rule('equipment_block', r => r.seq(
          r.keyword('equipment'),
          r.block(r.choice(
            r.seq(r.keyword('head'),    r.colon(), r.expression()),
            r.seq(r.keyword('main'),    r.colon(), r.expression()),
            r.seq(r.keyword('offhand'), r.colon(), r.expression()),
            r.seq(r.keyword('legs'),    r.colon(), r.expression()),
            r.seq(r.keyword('chest'),   r.colon(), r.expression()),
            r.seq(r.keyword('boots'),   r.colon(), r.expression()),
            r.seq(r.keyword('helmet'),  r.colon(), r.expression()),
            r.seq(r.keyword('chestplate'), r.colon(), r.expression()),
            r.seq(r.keyword('leggings'),   r.colon(), r.expression()),
            r.seq(r.keyword('boots'),       r.colon(), r.expression()),
          ))
        )),

        // Drops block
        rule('drops_block', r => r.seq(
          r.keyword('drops'),
          r.block(r.oneOrMore(r.seq(
            r.expression(),           // item
            r.expression().optional(), // chance
            r.keyword('amount').optional(r.expression()) // optional amount
          )))
        )),

        // Experience
        rule('experience_field', r => r.seq(
          r.keyword('experience'),
          r.expression()
        )),

        // Skills block (maps event names to skill lists)
        rule('skills_block', r => r.seq(
          r.keyword('skills'),
          r.block(r.zeroOrMore(r.seq(
            r.choice([
              r.seq(r.keyword('on_spawn'),      r.trigger_threshold().optional()),
              r.seq(r.keyword('on_death'),      r.trigger_threshold().optional()),
              r.seq(r.keyword('on_damage'),     r.trigger_threshold().optional()),
              r.seq(r.keyword('on_damaged'),    r.trigger_threshold()),
              r.seq(r.keyword('on_tick'),       r.keyword('every').optional(), r.expression()), // every N ticks
              r.seq(r.keyword('on_target'),      r.trigger_threshold().optional()),
              r.seq(r.keyword('on_interact'),    r.trigger_threshold().optional()),
              r.seq(r.keyword('on_explode'),    r.trigger_threshold().optional()),
              r.seq(r.keyword('on_enter_combat'), r.trigger_threshold().optional()),
              r.seq(r.keyword('on_leave_combat'), r.trigger_threshold().optional()),
            ]),
            r.block(r.oneOrMore(r.skill_call()))
          )))
        )),

        // Trigger threshold: >50%, <25%, =100%
        rule('trigger_threshold', r => r.seq(
          r.oneOf(['>', '<', '=']),
          r.expression()
        )),

        // Skill call: name arg arg arg...
        rule('skill_call', r => r.seq(
          r.identifier(),
          r.zeroOrMore(r.expression())
        )),
      ]
    })
  ]
})
```

### Inklang Source Syntax

The grammar above enables this Inklang syntax:

```ink
mob Zombie {
  name: "§cUndead Warrior"
  health: 200
  damage: 8
  tier: 3

  equipment {
    head: DIAMOND_HELMET
    main: IRON_SWORD
  }

  drops {
    IRON_INGOT 50%
    DIAMOND 5% 1
    BONE 100% 2-4
  }

  experience: 50

  skills {
    on_spawn {
      particle_effect "happyVillager" 10
      message "§6A powerful zombie rises!"
    }

    on_death {
      sound "entity.zombie.death" 1.0
      explosion 2
    }

    on_damaged >50% {
      speed_boost 2 5s
    }

    on_tick every 20 {
      // every 20 ticks (1 second), check if health is low
      if entity.health() < 50 {
        particle_effect "drip lava" 5
      }
    }
  }
}

mob Creeper {
  name: "§aCharged Creeper"
  health: 100
  damage: 6

  skills {
    on_explode {
      summon "lightning_bolt" ~0 ~0 ~0
    }

    on_death {
      explosion 5
    }
  }
}

mob BossWither {
  name: "§4§lThe Wither"
  health: 5000
  damage: 15
  tier: 5

  skills {
    on_spawn {
      message "§c§lTHE WITHER HAS AWAKENED"
      sound "entity.wither.spawn" 2.0
      particle_effect "smoke" 50
    }

    on_damaged >75% {
      speed_boost 3 10s
      message "§cThe Wither is enraged!"
    }

    on_damaged >25% {
      summon "wither_skull" ~1 ~1 ~0
      explosion 3
    }

    on_death {
      message "§a§lThe Wither has been defeated!"
      explosion 10
      summon "firework_rocket" ~0 ~5 ~0
    }
  }
}
```

---

## Runtime Architecture

### Kotlin Side (MobHandler.kt — existing)

The Kotlin `MobHandler` already handles the `mob` declaration keyword. It:
1. Extracts the entity type name from the declaration name
2. Extracts handler funcIdxs for each event clause
3. Registers a `MobListener` for the entity type

**Changes needed:** Extend `MobListener` to support:
- Equipment application on spawn
- Drop table registration
- XP configuration
- Skill execution context

### TypeScript Side (Runtime Library)

The TypeScript runtime library (`lib/runtime/`, `lib/skills/`) is bundled into the package and loaded at runtime by the ink-bukkit plugin's grammar extension loader. It provides:

1. **Skill implementations** — the actual logic for each skill
2. **Context objects** — `SkillContext` exposing `entity`, `target`, `damage`, `server`
3. **Equipment/mechanics helpers** — apply gear, handle drops

### Skill Execution Flow

```
MobListener.event fires
  → extract funcIdx for event
  → create SkillContext { entity, target, damage, ... }
  → execute compiled Ink function
  → Ink function calls built-in skill macros
  → macro dispatches to lib/skills/*.ts implementation
  → implementation reads mob metadata, modifies entity state
```

### Skill Context Interface

```typescript
interface SkillContext {
  entity: Entity                      // the mob entity
  target: Entity | null               // current attack target (if any)
  damage: number                      // damage amount (for on_damage, on_damaged)
  server: Server                      // PaperMC Server reference
  trigger: string                     // event name that fired
  metadata: Record<string, Value>     // mob-level custom fields
  skillArgs: Value[]                  // args passed to the skill call
  world: World                        // entity.world
  location: Location                  // entity.location
}
```

---

## Skill Catalog (v0.1.0)

### Effects

| Skill | Args | Description |
|-------|------|-------------|
| `particle_effect` | `effect: string, count: number` | Spawn particle effect at entity location |
| `sound` | `sound: string, volume: number` | Play sound at entity location |
| `explosion` | `radius: number` | Create explosion at entity location |
| `message` | `text: string` | Send message to world (or player if targeted) |

### Combat

| Skill | Args | Description |
|-------|------|-------------|
| `damage` | `amount: number, cause?: string` | Deal damage to target |
| `heal` | `amount: number` | Heal entity by amount |
| `apply_effect` | `effect: string, duration: number` | Apply potion effect (seconds) |

### Utility

| Skill | Args | Description |
|-------|------|-------------|
| `speed_boost` | `level: number, duration: number` | Apply speed boost (seconds) |
| `jump_boost` | `level: number, duration: number` | Apply jump boost |
| `teleport` | `x, y, z: number, world?: string` | Teleport entity to coordinates |
| `summon` | `entity_type: string, x, y, z: string` | Spawn entity (use `~` for relative offset) |
| `remove` | — | Remove the entity from the world |

### Logic

| Skill | Args | Description |
|-------|------|-------------|
| `if` | `condition: Expr, then: Skill[], else?: Skill[]` | Conditional skill execution |
| `else` | `skills: Skill[]` | Else branch (used inside `if`) |

### Meta-Only (Not skills)

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Custom display name (supports color codes) |
| `health` | number | Max health (HM) |
| `damage` | number | Base attack damage |
| `tier` | number | Internal tier/rank for scaling |
| `drops` | block | Item drop table (see Drops section) |
| `equipment` | block | Entity equipment loadout |
| `experience` | number | XP granted on death |

---

## Drops

Drop syntax inside the `drops {}` block:

```ink
drops {
  IRON_INGOT 50%       # 50% chance, default amount 1
  DIAMOND 5% 1         # 5% chance, exactly 1
  BONE 100% 2-4        # 100% chance, 2 to 4 items
  GOLD_APPLE 10% 1-2 { # with conditions
    if entity.tier() >= 3
  }
}
```

Drop implementation:
- Parse item material name, chance percentage, optional amount range
- On entity death, roll each drop against chance
- If chance passes, drop item stack at entity location
- Optional inline condition checked before drop

---

## Equipment

Equipment block syntax:

```ink
equipment {
  head: DIAMOND_HELMET
  main: IRON_SWORD
  offhand: SHIELD
  legs: CHAINMAIL_LEGGINGS
  boots: IRON_BOOTS
}
```

Implementation:
- On `on_spawn`, read the equipment map
- Resolve each item to a Bukkit `ItemStack`
- Set entity equipment slots via `Entity.setEquipment()`

---

## Threshold Triggers

Trigger conditions on skill blocks:

```ink
on_damaged >50% { }   // fires when health drops below 50%
on_damaged <25% { }   // fires when health drops below 25%
on_damaged =100% { }  // fires on every damage event
```

Internally, `on_damaged >50%` means: only execute if `entity.health() / entity.maxHealth() < 0.50` **after** the damage was applied (i.e., it crossed the threshold).

---

## Compilation Pipeline

1. **Author writes** `*.ink` source using `mob` grammar
2. **Printing Press** compiles each `.ink` → `.inkc` (JSON bytecode chunk)
3. **ink-bukkit** loads `.inkc` chunks at plugin enable time
4. **MobHandler** parses the `mob` declaration CST, extracts metadata + handler funcIdx
5. **MobListener** registers for PaperMC entity events
6. **Event fires** → VM executes the corresponding compiled function
7. **Compiled function** calls built-in skill macros → dispatches to TypeScript runtime

The skill macros are **built-in functions registered at compile time** by the `ink.mobs` package, not by the ink VM itself. When the compiled chunk references a skill like `particle_effect`, it calls a registered built-in with the skill name + args.

---

## Open Questions / Deferred

- [ ] **Skill targeting** — `damage` currently hits `target`. Should support `@self`, `@trigger`, `@location`, `@world` etc.?
- [ ] **Cooldowns** — skill cooldowns per mob instance?
- [ ] **Randomized skills** — `random { skill1 50% skill2 50% }`
- [ ] **Skill chains** — sequential vs parallel execution (`then`, `also`)
- [ ] **Variable persistence** — mob-level variables that survive across events?
- [ ] **Condition language** — `if entity.health() < 50` uses Ink expressions; enough?
- [ ] **Phase system** — MythicMobs-style phases for boss escalation
- [ ] **Custom AI goals** — programmatic pathfinding/navigation goals
- [ ] **Scalable difficulty** — `tier` field to scale all stats by a multiplier
