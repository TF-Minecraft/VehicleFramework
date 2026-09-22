# CachyOS lifecycle playtest — 2026-09-22

Environment: disposable Paper 1.21.10 build 130 server, ModelEngine R4.1.1,
ProtocolLib 5.4.0, supplied TLibs 1.0 and vehicle/model assets. The baseline was
`bfdb59b` (the mount-recovery implementation inherited by this PR). Tests used a
Minecraft 1.21.10 protocol client for seat-menu clicks and driving input, a temporary
server probe for authoritative entity/seat state, and the isolated full graphical
client on the RTX 3070 for visual inspection. No production database was touched.

## Reproduced unload defect

1. On the baseline, spawn an unoccupied car in an unattended chunk.
2. Observe Paper's entity-removal/unload events: `isValid=false`, `isDead=false`.
3. ModelEngine destroys the model before the later chunk-unload callback. VF still
   retains the vehicle. The probe recorded `bones=0`, `modelDestroyed=true`,
   `modelRegistered=false`, while the vehicle remained in the VF registry.
4. Loading the chunk again did not rebuild that registered vehicle's model.

The first revision of this PR also exhibited this failure. The final fix listens
to `EntitiesUnloadEvent`, permits saving unloaded but non-dead entities, and
compares chunk coordinates without calling `Location.getChunk()` during unload.
On the patched build the equivalent unattended car was saved to SQLite with
`passengers={}` and removed from the live registry. Later loading reconstructed an
85-bone model; boarding and driving passed. A subsequent genuine chunk unload and
reload created another backing entity and again passed boarding/driving.

This reproduces a lifecycle defect consistent with phantom/broken vehicles. It
does not establish that every intermittent boarding report has the same cause.

## Boarding and removal checks

| Scenario | Observation |
| --- | --- |
| Baseline fresh car and explicit SQLite unload/load | Boarding and driving passed; 45 occupied probe samples across two backing entities. |
| Baseline car never ridden before a full server restart | Empty passenger payload verified while stopped; subsequent boarding/driving passed in 26 occupied samples. The specific reported intermittent failure was not reproduced here. |
| Patched car never ridden before a full server restart | Empty passenger payload verified; restored model had 85 bones; subsequent boarding/driving passed in 24 occupied samples. |
| Patched biplane with saved `biplane_black` skin, never ridden before restart | Restored alternate model had 81 bones; boarding passed in 11 occupied samples. Full graphical client showed the rider in the cockpit. Flight was not tested. |
| Patched unattended car, later loaded and then unloaded/reloaded again | Boarding/driving passed in 22 occupied samples across two backing entities. |
| Explicit unload followed by load | Unload returned success, old backing entity became invalid, registry entry disappeared, and load constructed a new backing entity. |
| Remove the occupied alternate-skin biplane | Old backing entity became invalid, VF registry entry disappeared, and graphical client showed the model gone. |

Every occupied sample above retained a live registered model, the correct mount
manager, and agreement between the requested seat, passenger map and controller.
The probe also checked rider position against the animated seat. Bukkit
`isInsideVehicle()` is false even for successful ModelEngine seats; it is not an
appropriate pass/fail assertion. Likewise, this protocol client's cached virtual
seat position lagged behind; authoritative positions and graphical inspection were
used instead.

## Buoyancy

A sloop in a seven-block-deep pool was sampled every five server ticks. With
gravity enabled, median observed ascent was `0.0166667` blocks/tick and descent
`-0.0083333` blocks/tick. The previous commanded ascent was `0.05` blocks/tick.
Moving the sloop to the floor at Y=-60 resulted in a steady ascent to the surface
band near Y=-53.6 within 385 ticks; it did not remain grounded.

The initial no-gravity test found that an armor stand accepted velocity but did
not travel: its Y coordinate stayed constant for 160 ticks. The final fix applies
vertical movement through ModelEngine's collision-aware entity-move API for
no-gravity armor stands. The final build then passed a 180-tick live run
(37 samples), with median ascent `0.0166667` and descent `-0.0083333` blocks/tick.

Centre-only depth selection also has unit coverage, including a solid floor with
two water blocks above it. The live recovery check used a full pool; it does not
claim to cover every shoreline shape or flowing-water configuration.

## Evidence and limits

The final clean Maven `verify` passed all 398 tests. The chunk-matching regression
also asserts that matching an invalid unloading entity never loads a chunk.
Temporary probe traces, console logs, screenshots and exact tested jar hashes are
retained in the private lab's `artifacts/sqlite-lifecycle-20260922/` directory.
The probe is test instrumentation, not part of the shipped plugin.

These tests exercise the supplied runtime and models. They do not prove the absence
of all server-specific races, repair existing unregistered phantoms, or establish
crash-time freshness of every SQLite row. See [the lifecycle review](sqlite-lifecycle-review.md)
for the remaining persistence limitations.

## Merge validation

After integrating main's Java 21 / TLibs 1.1.1 build update, version 1.1.14
passed a clean Maven `verify` on Java 21 (398 tests). The lab observations above
precede that dependency update; they are not a new playtest of the updated TLibs
release.
