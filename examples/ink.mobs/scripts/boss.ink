mob Wither {
  on_spawn {
    print("THE WITHER HAS AWAKENED")
  }

  on_damage {
    print("The Wither is enraged!")
  }

  on_death {
    print("The Wither has been defeated! A star appears...")
  }
}

mob EnderDragon {
  on_spawn {
    print("The Ender Dragon stirs...")
  }

  on_death {
    print("The dragon is slain! The End is free.")
  }
}
