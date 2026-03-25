mob Zombie {
  on_spawn {
    let loc = entity.location()
    print("Zombie spawned at ${loc.x()}, ${loc.y()}, ${loc.z()}")
  }

  on_death {
    print("Zombie was slain!")
  }

  on_damage {
    print("Zombie took ${damage} damage! Health: ${entity.health()}")
  }
}

mob Skeleton {
  on_spawn {
    print("A skeleton appeared")
  }

  on_target {
    print("Skeleton is targeting: ${target.name()}")
  }
}
