// Example: Armored Zombie
// Demonstrates equipment, drops, experience, and skills

mob ArmoredZombie {
  equipment {
    head: DIAMOND_HELMET
    chest: DIAMOND_CHESTPLATE
    legs: IRON_LEGGINGS
    boots: GOLDEN_BOOTS
    main: IRON_SWORD
  }

  drops {
    IRON_INGOT 30
    DIAMOND 5 1
    ROTTEN_FLESH 80 2
  }

  experience: 50

  skills {
    on_spawn {
      sound(entity, "ENTITY_ZOMBIE_AMBIENT", 1.0, 1.0)
      ignite(entity, 5)
    }

    on_damaged gt 5 {
      particle_effect(entity, "DAMAGE_INDICATOR", 0.5, 0.5, 0.5, 0.01, 10)
      sound(entity, "ENTITY_ZOMBIE_HURT", 1.0, 1.2)
    }

    on_death {
      sound(entity, "ENTITY_ZOMBIE_DEATH", 1.0, 1.0)
      explosion(entity, 0.0, false, false)
    }
  }
}

// Example: Basic Zombie with simple handlers
mob Zombie {
  on_spawn {
    print("A zombie shambles into existence")
  }

  on_death {
    print("The zombie collapsed!")
  }

  on_damage {
    print("Zombie took damage!")
  }
}
