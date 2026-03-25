// Example: Boss Mobs
// Demonstrates high-end equipment, multi-skill triggers, and dramatic effects

mob WitherBoss {
  equipment {
    head: NETHER_STAR
    main: DIAMOND_SWORD
    off: SHIELD
  }

  drops {
    NETHER_STAR 100 1
    DIAMOND 100 10
    GOLD_INGOT 100 16
    WITHER_SKELETON_SKULL 50 1
  }

  experience: 500

  skills {
    on_spawn {
      print("THE WITHER HAS AWAKENED")
      sound(entity, "ENTITY_WITHER_SPAWN", 1.0, 1.0)
      particle_effect(entity, "SMOKE_LARGE", 2.0, 2.0, 2.0, 0.1, 50)
      set_max_health(entity, 600.0)
      set_health(entity, 600.0)
      speed_boost(entity, 2.0, 1200)
    }

    on_damaged gt 50 {
      print("The Wither is enraged!")
      particle_effect(entity, "SMOKE_LARGE", 3.0, 2.0, 3.0, 0.2, 30)
      sound(entity, "ENTITY_WITHER_HURT", 1.0, 0.8)
    }

    on_damaged gt 200 {
      print("THE WITHER RAGES!")
      explosion(entity, 3.0, true, false)
      speed_boost(entity, 3.0, 200)
    }

    on_death {
      print("The Wither has been defeated! A star appears...")
      sound(entity, "ENTITY_WITHER_DEATH", 1.0, 1.0)
      explosion(entity, 5.0, true, true)
      summon(entity, "WITHER", entity.x, entity.y + 2.0, entity.z)
    }
  }
}

mob EnderDragon {
  equipment {
    head: DRAGON_HEAD
    main: DRAGON_EGG
  }

  drops {
    DRAGON_EGG 100 1
    ENDER_PEARL 100 20
    EXPERIENCE_BOTTLE 100 50
  }

  experience: 1000

  skills {
    on_spawn {
      print("The Ender Dragon stirs in the End...")
      sound(entity, "ENTITY_ENDER_DRAGON_GROWL", 1.0, 1.0)
      set_max_health(entity, 500.0)
      set_health(entity, 500.0)
    }

    on_damaged gt 30 {
      print("The Ender Dragon was struck!")
      particle_effect(entity, "DRAGON_BREATH", 1.0, 1.0, 1.0, 0.05, 15)
    }

    on_death {
      print("The dragon is slain! The End is free.")
      sound(entity, "ENTITY_ENDER_DRAGON_DEATH", 1.0, 1.0)
      explosion(entity, 8.0, false, true)
    }
  }
}
