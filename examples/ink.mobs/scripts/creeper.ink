// Example: Charged Creeper and Regular Creeper
// Demonstrates on_explode, damage skills, and equipment

mob ChargedCreeper {
  equipment {
    main: GOLDEN_APPLE
  }

  drops {
    DIAMOND 100 3
    GOLD_INGOT 50 2
    EMERALD 25 1
  }

  experience: 100

  skills {
    on_spawn {
      print("A charged creeper materializes... ominous!")
      set_max_health(entity, 40.0)
      set_health(entity, 40.0)
    }

    on_damaged gt 0 {
      ignite(entity, 8)
      particle_effect(entity, "ELECTRIC_SPARK", 1.0, 1.0, 1.0, 0.05, 20)
    }

    on_explode {
      explosion(entity, 6.0, true, true)
      sound(entity, "ENTITY_CREEPER_PRIMED", 1.0, 1.0)
    }
  }
}

mob Creeper {
  on_spawn {
    print("A creeper lurks in the shadows...")
  }

  on_target {
    print("CREEPER IS LOCKING ON")
    set_velocity(entity, 0.0, 0.3, 0.0)
  }

  on_death {
    print("The creeper is no more!")
  }

  on_damage {
    print("Creeper took a hit!")
  }
}

mob Enderman {
  drops {
    ENDER_PEARL 30 1
    ENDER_EYE 10 1
  }

  experience: 30

  skills {
    on_spawn {
      print("An Enderman materialized from the void")
      teleport(entity, entity.x, entity.y + 5.0, entity.z)
    }

    on_target {
      print("ENDERMAN HAS LOCKED ON")
      has_tag(entity, "angry")
      if has_tag(entity, "angry") {
        damage(entity, 2.0)
      }
    }

    on_death {
      print("The Enderman returned to the void")
    }
  }
}
