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

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/VehicleFramework/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

[User guide](https://github.com/TF-Minecraft/Docs/blob/main/projects/VehicleFramework/docs/playing.md): commands, models, vehicle YAML, weapons, and trains.

## Tests

With Java 21 and the pinned plugin dependencies installed (see the Build workflow), run:

```sh
mvn -B --no-transfer-progress clean verify
```

JUnit 5 and Mockito cover track geometry, train routing and placement, persistence,
locomotive behaviour and other logic that runs without a server. They do not start a
live Paper server or load ModelEngine models. The Build workflow runs the suite on pull
requests targeting `main` and on pushes to `main` and uploads the Surefire reports. JaCoCo requires 100% line coverage across all production classes, with no
coverage exclusions. HTML and XML reports are written to `target/site/jacoco/`
and uploaded by the Build workflow.

bStats is bundled from the upstream `bstats-bukkit:3.1.0` Maven artifact and
relocated into the plugin's namespace. This replaces the same-version vendored
copy; third-party dependency bytecode is outside the plugin's production-source
coverage denominator. Metrics configuration and lifecycle integration remain tested.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
