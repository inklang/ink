mob Creeper {
  on_spawn {
    print("A creeper lurks in the shadows...")
  }

  on_death {
    print("The creeper is no more!")
  }

  on_damage {
    print("Creeper took a hit!")
  }
}

mob Enderman {
  on_spawn {
    print("An Enderman materialized")
  }

  on_target {
    print("ENDERMAN HAS LOCKED ON")
  }

  on_death {
    print("The Enderman returned to the void")
  }
}
