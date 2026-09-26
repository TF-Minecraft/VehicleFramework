# VehicleFramework

> Custom vehicles, transport, and vehicle combat for TF-Minecraft.

VehicleFramework brings modeled vehicles into the Minecraft world with their own movement, seats, components, and controls. Its systems support ground travel, boats, aircraft, and trains, letting each vehicle combine the features suited to its role.

Fuel, damage, ownership, and repairs make vehicles persistent parts of the world, with uses ranging from passenger transport and hauling to armed encounters.

## Features

- **Different ways to travel** — movement systems for ground vehicles, floating hulls, wings, balloons, and rail travel.
- **Drivers and passengers** — multiple seats, ownership rules, access lists, and vehicle tickets control who can ride.
- **Vehicle upkeep** — fuel tanks, damageable components, and repairs give vehicles ongoing maintenance needs.
- **Cargo and towing** — vehicle containers and towing support transport beyond individual passengers.
- **Mounted weapons** — vehicle weapons use ammunition systems including bullets, projectiles, bombs, and torpedoes.
- **Connected trains** — track and carriage systems support railway vehicles and linked consists.

## Related projects

[VFBuilders](https://github.com/TF-Minecraft/VFBuilders) adds vehicle construction gameplay around VehicleFramework.

Originally created by [Drefvelin](https://github.com/Drefvelin).

## Locomotive overdrive

The simple locomotive runs 20% faster (`speed: 0.72`) and burns `1.25` fuel units
per cycle. Its riders, including those in attached cars, take 75% less incoming
damage, retaining the existing damage cap. Other vehicles keep their existing balance.

Use W/S to set forward throttle up to 120%. A shared boost budget lasts about
20 seconds at 110% or 10 seconds at 120%; changing throttle above 100% spends
the same budget. Fuel consumption follows a quadratic curve:

| Throttle | Fuel per cycle | Extra fuel |
| --- | --- | --- |
| 100% | 1.25 | 0% |
| 110% | 1.5625 | 25% |
| 120% | 2.5 | 100% |

Exhausting boost or returning to 100% starts a five-minute cooldown. Normal 100%
throttle remains available throughout cooldown. The scoreboard shows boost and
cooldown time. Engine damage retains its normal throttle limit, so overdrive
requires a fully healthy engine. Reverse remains limited to -100%.

Boost and cooldown state survive saving and reloading; timers include unloaded
time. Install the updated plugin and matching ServerAssets vehicle YAML,
including `behaviour.train.locomotive: true` on the locomotive. Plugin updates
do not overwrite existing vehicle configurations.

## Documentation

[User guide](https://github.com/TF-Minecraft/Docs/blob/main/projects/VehicleFramework/docs/playing.md): commands, models, vehicle YAML, weapons, and trains.

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/VehicleFramework/README.md) in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs) also holds the architecture notes.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
