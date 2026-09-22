# SQLite lifecycle review

Compared the last JSON implementation (`20f8a74`) with the starting SQLite branch
(`7bbe8fd`), then integrated the fixes onto TF-Minecraft main (`39c239d`),
preserving its rejected-mount reload workflow and walking controller. This is a
code and automated-test comparison, not an inspection of a
production database or a reproduction on the live Minecraft server.

## Storage versus runtime ownership

Both versions decode vehicle data into `IncompleteVehicle` and construct a new
`ActiveVehicle` through `VehicleSpawner`. SQLite retains the JSON payload in
`payload_json`; it does not serialize ModelEngine objects or mount-manager
references. Seat assignments are stored by bone name, with player names or entity
UUIDs. The JSON loader deleted its input file while loading; SQLite retains the row,
checks live UUIDs before spawning, and tombstones permanently destroyed vehicles.

The saved model, runtime handlers and entity must still have one owner. A valid
SQLite row cannot guarantee that runtime construction or cleanup succeeded.

## Findings and fixes

| Finding | Origin | Change |
| --- | --- | --- |
| Passengers were mounted before loading replaced and destroyed the model, even for the same skin. | Also present in JSON version. | Select the saved skin before constructing any handler; restore passengers after registration and consist linking. No model replacement during loading. |
| A construction exception could leave a spawned base/model outside the vehicle registry. | Also present in JSON version. | Roll back partial spawns and failures after registration. Preserve the stored row for retry. Reject missing saved skins/models before spawning. |
| Removal set its one-shot flag before dismount/relink callbacks, with base removal only at the end. A failure could prevent removal permanently. | Also present in JSON version. | Unregister and tear down ModelEngine in a `finally` path; base entity removal runs even if model cleanup throws. Clear control/menu/tow references on unregister. |
| Base entities and ModelEngine models could be independently persisted outside SQLite. | Shared runtime setup; retained SQLite rows make independent restoration especially problematic. | Disable Bukkit entity persistence and ModelEngine model saving for newly spawned runtime vehicles. |
| Chunk-unload matching excluded dead/invalid entities, leaving stale registry entries that could block SQLite respawn. | SQLite-era chunk-unload code. | Match location even if Bukkit already invalidated the unloading entity. |
| A successful mount return value could still leave missing controller registration or a binding to the wrong seat. Mouse bindings bypassed the packet validation. | Runtime mount handling. | Require the requested seat, passenger map, passenger membership and rider controller to agree before recording occupancy. Gate all vehicle key input on that validation. |
| Seafloor water was classified as shallow from the block below, regardless of water above. Deep boats could be outside the surface search range. | Boat physics, independent of persistence. | Two water blocks in the centre column override ground selection. Deeply submerged floating boats receive upward lift even beyond the local surface scan. |

Bobbing ascent changes from `0.05` to `0.05 / 3` blocks per tick. Descent uses half
that commanded speed. Gravity-enabled entities retain their falling velocity,
capped to the slow descent speed; entities without gravity receive an explicit
slow downward velocity. Native server physics still needs an in-game check.

## Evidence and remaining checks

- Maven build and 398 tests pass.
- Three new mount regressions fail against the original `SeatHandler` and pass
  against the patch: absent controller, accepted wrong seat, and stale wrong-seat
  control authorization.
- Added failure-path entity cleanup, invalid-entity chunk matching, centre-only
  depth sampling, gravity/no-gravity bobbing and SQLite close/reopen tests. The
  SQLite test preserves UUID, type, skin, fuel, throttle and player/entity seats.
- Existing persistence tests cover revisions, tombstones, corrupt payloads, chunk
  queries and save failures.

No plugin was deployed and no live database was changed. In-game verification
should cover boarding after chunk unload/reload and restart, saved non-default
skins, destroy/unload entity counts, gravity/no-gravity bobbing, and recovery from
the bottom of a deep pool with only the centre column clear. Existing unregistered
phantoms have no reliable ownership marker and are not automatically deleted by
this patch.

Storage failure handling retains its existing limitations: an older SQLite row
can qualify as `alreadyStored` after a failed current save, and new vehicles are
normally persisted on checkpoint/unload. These tests do not establish crash-time
freshness or prove the absence of every ModelEngine/server-specific visual issue.
