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

## Train wheel animations

Trains with `behaviour.train.wheel-diameter` hold their wheel pose while stopped
and preserve it when changing direction. Their configured forward/backward
animation lists pair by order; each pair must be mirrored looping animations of
one wheel turn. Wheel speed continues to follow travel speed and wheel diameter.

## Laying curves

Extending a track keeps its bends local. A stroke curves at `curve-radius`
(`trains.yml`, 32 blocks by default) and runs straight elsewhere; a stroke too
short for that radius curves tighter, down to the sharpest turn that
`max-turn-degrees` allows over `min-lay-distance`.

- A click just beside the row the rail is on (up to 3 blocks, within 8 degrees)
  keeps the rail on its row and shifts it across on a reverse curve just before
  the click. Laying 1,000 blocks with the end one block over leaves 990 blocks
  on the row.
- Turning onto a row curves at the corner where the two headings meet, with
  straight track either side.
- A track end left off the rows turns onto the row first when a corner would
  leave a long run off it, then shifts across near the click.
- Joining two track ends meets the far track along its own heading, without a kink.

Other clicks lay a single arc, as before. Existing track is unchanged.

## Junctions in reverse

Hold A or D to choose a turnout within `junction-arm-distance` of the leading
wheels. Left and right are viewed in the direction the train is travelling.
While stopped, the throttle selects the approach direction; at zero throttle,
the train remembers its last direction.

Choose before the first wheels enter. The points stay locked until the whole
train clears, including when the last carriage leads while reversing. Stopping,
reversing midway, or saving and loading keeps every coupled car on the same
route. Closely spaced junctions retain their own choices while the train spans
them. Coming out of a branch follows its connection back onto the main track.

Train facing and occupied junction choices are saved with the consist. Existing
saves and throttle tapes remain readable. Before downgrading this version,
restore the matching vehicle-data backup: older versions cannot represent a
train facing the opposite way along a track.

## Track ends

Set `behaviour.train.wheel-bones` to the model bones at the frontmost and rearmost
axle pivots. Their model positions and scale determine where rail support ends.
The whole train stops before either axle runs past an open track end, in either
direction; cars already overhanging can drive back onto the rails. Connected
junctions and loop seams remain traversable.

Install the matching ServerAssets vehicle YAML with this plugin update to check
the outer axles of the locomotive, coal car, passenger car, and flat car. Without
`wheel-bones`, configured bogie pivots are checked; rigid cars retain centre checks.

## Bogies and skins

`behaviour.train.bogies` names two bogie bones; the car rests on the rail under
each, and each bogie turns to follow the rail under it. A vehicle's skins share
this setting, so each skin's model is checked for the bones: a skin built without
them, such as the Simple Locomotive's original model, is placed as a rigid car.
Changing skin sets the bogies up again for the new model; skins that share the
bogie bones keep their bogie angles.

Install this plugin update before a ServerAssets vehicle YAML that gives
`bogies` to a vehicle whose skins do not all have those bones, such as the
Simple Locomotive, whose original skin has none.

## Walkable decks

Train cars can have a deck that players walk on, and players standing on it ride
along with the train. The flat car (`flat_car`, model in ServerAssets) is the only
car that has one: an open deck with no seats, for riders to stand on. Set a deck
under `behaviour.train.walkable` in a car's YAML, in the model's blocks from its
origin:

```yaml
walkable:
    x: [-1.5, 1.5]      # across the car
    z: [-4.5, 4.5]      # along it; +z faces the front
    top: 1.3125         # height of the deck
    box-size: 1.5       # optional; at most 3
```

The deck is made of invisible shulkers, which players can stand on. Minecraft
does not rotate their boxes, so on bends they overhang the car's corners a little;
smaller boxes overhang less. Clicks and hits on the deck go to the car. Players are
carried up to `walkable.carry-max-speed` in `trains.yml`, 1.0 blocks a tick by
default; faster than that, the deck slides out from under them. The boxes are
never saved; any left behind when a car unloads are removed when their chunk
loads again. Plugin updates do not overwrite existing vehicle configurations, so
add `flat_car.yml` by hand.

## Documentation

[User guide](https://github.com/TF-Minecraft/Docs/blob/main/projects/VehicleFramework/docs/playing.md): commands, models, vehicle YAML, weapons, and trains.

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/VehicleFramework/README.md) in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs) also holds the architecture notes.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
