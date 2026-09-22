# SQLite lifecycle review

Compared the last JSON implementation (`20f8a74`) with the starting SQLite branch
(`7bbe8fd`), then integrated the fixes onto TF-Minecraft main (`39c239d`),
preserving its rejected-mount reload workflow and walking controller. Code review and automated tests were followed by a CachyOS lab playtest on
Paper 1.21.10 build 130 and ModelEngine R4.1.1 using the supplied assets.
No production database was inspected or changed. See
[the playtest results](sqlite-lifecycle-playtest.md).

## Storage versus runtime ownership

Both versions decode vehicle data into `IncompleteVehicle` and construct a new
`ActiveVehicle` through `VehicleSpawner`. SQLite retains the JSON payload in
`payload_json`; it does not serialize ModelEngine objects or mount-manager
references. Seat assignments are stored by bone name, with player names or entity
UUIDs. The JSON loader deleted its input file while loading; SQLite retains the row,
checks live UUIDs before spawning, and tombstones permanently destroyed vehicles.

The saved model, runtime handlers and entity must still have one owner. A valid
SQLite row cannot guarantee that runtime construction or cleanup succeeded.

## Clarification of the reported boarding failure

The reporter was not riding the vehicle when it loaded from the database. Mounting
saved passengers before replacing the model therefore does not establish the
cause of that occurrence. The old load path still replaced the model when there
were no saved passengers, but it also called `SeatHandler.updateModel` with the
replacement manager. Code inspection alone does not prove that this replacement
left the later boarding attempt with a stale manager.

The patch removes that unnecessary replacement and validates bindings on every
boarding attempt, including the first rider after an empty load. These are relevant
fixes. The lab reproduced an unattended entity-unload failure on the old build:
VF retained a registered vehicle after its ModelEngine model was destroyed.
That is a confirmed lifecycle defect consistent with the report, but the exact
reported intermittent boarding occurrence remains unconfirmed. Boarding after
an empty SQLite load passed on the patch after restart (default and alternate
skins) and after another chunk unload/reload.

## Findings and fixes

| Finding | Origin | Change |
| --- | --- | --- |
| Passengers were mounted before loading replaced and destroyed the model, even for the same skin. | Also present in JSON version. | Select the saved skin before constructing any handler; restore passengers after registration and consist linking. No model replacement during loading. |
| A construction exception could leave a spawned base/model outside the vehicle registry. | Also present in JSON version. | Roll back partial spawns and failures after registration. Preserve the stored row for retry. Reject missing saved skins/models before spawning. |
| Removal set its one-shot flag before dismount/relink callbacks, with base removal only at the end. A failure could prevent removal permanently. | Also present in JSON version. | Unregister and tear down ModelEngine in a `finally` path; base entity removal runs even if model cleanup throws. Clear control/menu/tow references on unregister. |
| Base entities and ModelEngine models could be independently persisted outside SQLite. | Shared runtime setup; retained SQLite rows make independent restoration especially problematic. | Disable Bukkit entity persistence and ModelEngine model saving for newly spawned runtime vehicles. |
| Paper invalidates entities before chunk unload; the old listener skipped them and the snapshot factory rejected them. The lab retained a registered vehicle with zero model bones. Chunk matching could also load unrelated chunks. | SQLite-era unload handling; reproduced on the pre-patch build. | Save and remove at `EntitiesUnloadEvent`; permit snapshots of unloaded but non-dead entities. Match fallback chunk coordinates without loading chunks. |
| A successful mount return value could still leave missing controller registration or a binding to the wrong seat. Mouse bindings bypassed the packet validation. | Runtime mount handling. | Require the requested seat, passenger map, passenger membership and rider controller to agree before recording occupancy. Gate all vehicle key input on that validation. |
| Seafloor water was classified as shallow from the block below, regardless of water above. Deep boats could be outside the surface search range. | Boat physics, independent of persistence. | Two water blocks in the centre column override ground selection. Deeply submerged floating boats receive upward lift even beyond the local surface scan. |

Bobbing ascent changes from `0.05` to `0.05 / 3` blocks per tick. Descent uses half
that commanded speed. Gravity-enabled entities retain their falling velocity,
capped to the slow descent speed; entities without gravity receive an explicit
slow downward motion. No-gravity armor stands skip native velocity travel, so
their vertical motion uses ModelEngine's collision-aware entity-move API.
Live gravity-enabled and no-gravity motion are checked in the playtest results.

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

The lab checks cover empty-load boarding after restart and chunk reload, saved
alternate skin, unload/delete entity cleanup, slower bobbing, and recovery from a
deep pool floor. The lab uses a disposable database. Existing unregistered
phantoms have no reliable ownership marker and are not automatically deleted by
this patch.

Storage failure handling retains its existing limitations: an older SQLite row
can qualify as `alreadyStored` after a failed current save, and new vehicles are
normally persisted on checkpoint/unload. These tests do not establish crash-time
freshness or prove the absence of every ModelEngine/server-specific visual issue.
