# Mica Hardening Checklist

## Architecture Overview

Mica uses Snapshot-Compute-Merge-Converge (SCMC) architecture to parallelize Minecraft's tick processing.
The overlay intercept lives at `LevelChunk.setBlockState()` — all vanilla game logic (onPlace, onRemove,
neighborChanged, cascades) runs naturally above it. Only unsafe operations are suppressed during parallel phases.

**Parallelized tick subsystems:**
- Random block ticks (crop growth, fire spread, vine growth, leaf decay)
- Entity ticks (AI, pathfinding, movement, collision)
- Scheduled block ticks (redstone repeaters, comparators, gravity blocks)
- Scheduled fluid ticks (water and lava flow)

**Still serial:**
- Block events (pistons, noteblocks)

---

## Thread Safety Issues

### 1. AsyncCatcher bypass
**File:** `AsyncCatcher.java`
**Current fix:** Blanket bypass for all ForkJoinWorkerThread calls.
**Proper fix:** Categorize each call site — only bypass operations explicitly
determined safe during parallel execution.

### 2. Bukkit event deferral and cancellation
**File:** `PaperEventManager.java`
**Current fix:** All sync events from ForkJoinWorkerThreads are buffered to DeferredEffects.
**Problem:** Cancellable events (BlockRedstoneEvent, BlockSpreadEvent) return immediately
without plugin processing. Plugins cannot cancel block changes during parallel execution.
**Severity:** Medium — plugins that cancel redstone/growth events will be ignored.
**Proper fix:** Either run cancellable events synchronously (defeating parallelism for
that operation) or implement speculative execution with deferred commit.

### 3. Entity spawns from parallel threads
**File:** `ServerLevel.addEntity()`
**Current fix:** Buffer in DeferredEffects, apply after parallel phase.
**Known issue:** UUID collisions observed — two falling gravel entities on different
threads received the same UUID via `Mth.createInsecureUUID()` which uses a shared
non-thread-safe random. Second entity silently dropped.
**Fix needed:** Use `UUID.randomUUID()` during parallel phase, or use per-thread random.
**Remaining concerns:**
- Entity position validity when deferred spawn executes
- Spawn reason correctness for all code paths

### 4. Network packet sending from worker threads
**Files:** `ServerLevel.broadcastEntityEvent()`, `broadcastDamageEvent()`,
`playSound()`, `sendParticles()`, `levelEvent()`, `gameEvent()`
**Current fix:** `sendBlockUpdated` and `setBlocksDirty` suppressed in
`notifyAndUpdatePhysics` during parallel phase. Other packet-sending methods
are NOT suppressed.
**Risk:** Iterating player tracking lists concurrently with main thread
modifications could cause ConcurrentModificationException.
**Severity:** Low crash risk, moderate data race risk.
**Proper fix:** Buffer all outbound packets in DeferredEffects during parallel
phase and flush on main thread.

### 5. Profiler thread safety
**File:** Various — `Profiler.get()` used throughout tick code.
**Current fix:** None.
**Risk:** Profiler accumulates stats in non-thread-safe data structures.
**Proper fix:** Disable profiler during parallel phases or use thread-local instances.

---

## Overlay Architecture

### 6. Transparent overlay at LevelChunk.setBlockState (CURRENT)
**Files:** `LevelChunk.setBlockState()`, `Level.notifyAndUpdatePhysics()`
**Approach:** Intercept at chunk level. All vanilla game logic runs naturally above it.
**Critical ordering:** `onPlace` must execute BEFORE the overlay verification early-return.
Previously, the overlay check returned null before `onPlace`, which prevented
`LiquidBlock.onPlace()` from scheduling continuation ticks — breaking water/lava flow.
**Suppressed during parallel phase:**
- `sendBlockUpdated` (packets)
- `setBlocksDirty` (rendering)
- `BlockPhysicsEvent` (Bukkit events)
- `updatePOIOnBlockStateChange` (POI)
- Heightmap updates
- Light engine updates
- Block entity create/remove (deferred to main thread)

### 7. Alternate Current per-thread WireHandler
**Files:** `Level.java` (micaWireHandler ThreadLocal), `ServerLevel.getWireHandler()`,
`LevelHelper.setWireState()`
**Current fix:** Per-thread WireHandler instances during parallel dispatch.
Each thread gets its own node cache, search queue, and `updating` flag.
**Note:** `LevelHelper.setWireState` bypasses `LevelChunk.setBlockState` — writes
directly to section palette. Overlay intercept in `LevelHelper` is still required.
Shape updates suppressed in the LevelHelper overlay path to prevent recursive chain spawning.

### 8. Vanilla redstone evaluator — FIXED
**Files:** `RedStoneWireBlock.java`, `RedstoneWireTurbo.java`
**Root cause:** `shouldSignal` was a shared boolean field. Cross-thread reads during
`getBlockSignal()` caused incorrect power levels and infinite oscillation.
**Fix:** Changed `shouldSignal` to `ThreadLocal<Boolean>`. All three redstone
implementations now work: VANILLA, EIGENCRAFT, ALTERNATE_CURRENT.

### 9. Per-thread neighbor updater
**Files:** `Level.java` (micaNeighborUpdater ThreadLocal), `ServerLevel.java`,
`Level.neighborShapeChanged()`
**Current fix:** `CollectingNeighborUpdater` created per-thread during parallel dispatch.
All `neighborChanged`, `shapeUpdate`, `updateNeighborsAt`, `neighborShapeChanged` calls
intercepted to route through per-thread instance.

### 10. Block entity state not snapshotted
**Current:** `TrackedOverlay.getBlockEntity()` falls through to live world.
**Risk:** Parallel tick reading a hopper's inventory while another thread modifies it.
**For random/entity ticks:** Low risk.
**For block entity ticking (future):** Must snapshot or synchronize.

---

## Entity Tick Safety

### 11. Entity position snapshots
**File:** `Entity.java`
**Current fix:** Snapshot position, bounding box, blockPosition, and inBlockState
before parallel entity dispatch. Cross-entity reads return snapshot values.
Self-entity reads return live values.
**Methods intercepted:** `getX()`, `getY()`, `getZ()`, `position()`, `getBoundingBox()`,
`blockPosition()`, `getBlockX/Y/Z()`, `getX/Y/Z(scale)`, `getEyeY()`, `getInBlockState()`
**Overhead:** ~5ns per cross-entity read (one field check + ThreadLocal.get()).

### 12. Entity chunk movement and removal deferral
**File:** `EntityLookup.java` (onMove and onRemove in EntityCallback)
**Current fix:** Both chunk slice moves AND entity removals are deferred to main thread
during parallel entity ticks. If deferred move fails due to stale chunk data, silently
catch and let next tick correct.
**Known behavioral effects (self-correct within 1-2 ticks):**
- Entity may be invisible to players briefly when crossing chunk boundaries
- Entity sensing/targeting may briefly miss entities with stale chunk assignments
- Despawn distance checks may be off by one chunk
  **onRemove deferral:** Prevents concurrent modification of `ChunkEntitySlices` hash maps
  during parallel entity ticking. Without this, chunk unload during/after parallel phase
  crashes with `Reference2ObjectOpenHashMap` NPE on corrupted iterator.

### 13. ChunkMap.newTrackerTick null chunk data
**File:** `ChunkMap.java`
**Current fix:** Null check on `moonrise$getChunkData()` — skip entity if null.
**Root cause:** Deferred entity chunk moves leave entities with null chunk data
until the deferred move applies.

### 14. TickThread.micaParallelPhase bypass
**File:** `TickThread.java`
**Current fix:** ThreadLocal boolean flag added to `isTickThread()` so Mica worker
threads pass tick thread checks.
**Risk:** May mask real thread-safety issues in code paths we haven't audited.

---

## Scheduled Tick System

### 15. Tick collection and willTickThisTick — FIXED
**File:** `LevelTicks.java`
**Root cause:** `collectForParallelDispatch` drained `toRunThisTick` before `toRunThisTickSet`
was populated. `willTickThisTick` always returned false, allowing duplicate tick scheduling.
**Fix:** Call `calculateTickSetIfNeeded()` eagerly before draining. Added `markTickExecuted()`
for per-tick removal during serial execution, matching vanilla's exact behavior.

### 16. Deferred scheduleTick
**File:** `LevelTicks.schedule()`
**Current fix:** `scheduleTick` calls during parallel phase are buffered in DeferredEffects,
tagged with the source OperationId. Replayed after merge for clean operations only.
Tainted operations' deferred ticks are discarded (serial re-run produces them directly).

### 17. Same-tick cascade drain
**Current fix:** After replaying deferred scheduled ticks on the clean path, drain any
ticks due for the current game tick in a loop until stable.
**Risk:** Edge cases in nested same-tick cascades.

### 18. Cascade conflict detection and merge optimization
**Files:** `TrackedOverlay.java`, `OverlayMerger.java`
**Cascade detection:** Positions read during a cascade triggered by a write are marked as
"touched." During merge, touched positions are treated as write-equivalent for
conflict detection — if another thread read a touched position, both operations
are tainted.
**Merge optimization:** Pre-built `Long2ObjectMap<List<OperationId>>` position→readers
index replaces repeated scans of `getOperationReads()`. Operation→written positions
index enables O(1) BFS walks. Merge time reduced from 1.68ms (86% of scheduled tick
time) to 0.23% of tick time — over 7x improvement.

### 19. Independent tick grouping (TickGrouper) — OPTIMIZED
**File:** `TickGrouper.java`
**Approach:** After merge, ticks are grouped into connected components via Union-Find
based on position overlap (writes, reads, touched). Components with conflicts are
re-run — potentially in parallel with each other since they share no positions.
**Performance:** Pre-built `Map<OperationId, LongOpenHashSet>` position index replaces
flat list scanning. Grouping is now O(total_positions) instead of O(ticks × total_reads).
**Fast path:** Skip grouper entirely when taintedOperations is empty (99%+ of game ticks).
**Parallel tainted re-run:** Multiple independent tainted groups dispatch to the ForkJoinPool,
each with its own overlay, neighbor updater, and wire handler. Overlays merge after
completion (guaranteed conflict-free since groups share no positions by construction).
Single tainted group runs serially on main thread to avoid dispatch overhead.

### 20. Flicker on tainted group serial re-run
**Status:** Mostly resolved. The `willTickThisTick` fix (item 15) eliminated the primary
cause of redstone flicker. Remaining minor flicker when tainted groups re-run serially
is due to individual block update packets sent mid-cascade.
**Severity:** Visual only — game state is correct.
**Proper fix:** Buffer all block update packets during scheduled tick phase and
flush atomically at the end.

---

## Deferred Operations

### 21. POI updates
**File:** `Level.notifyAndUpdatePhysics()` (suppressed during parallel)
**Current fix:** Suppressed during parallel phase. Applied when writes commit.
**Risk:** Villager AI may path to destroyed POI for 1 tick. Self-corrects.

### 22. Block entity create/remove
**File:** `LevelChunk.setBlockState()` (Mica intercept)
**Current fix:** Deferred to main thread via DeferredEffects.

### 23. Heightmap and lighting updates
**Current fix:** Skipped during parallel phase in `LevelChunk.setBlockState()`.
Applied when final writes commit to the live world.

---

## Performance Data

| Subsystem | Entities/Operations | Threads | Time | vs Serial |
|---|---|---|---|---|
| Random ticks | 98M reads | 8 | ~36ms | ~4x faster |
| Entity ticks | 800 villagers | 4 | 5ms | ~4x faster |
| Entity ticks | 1,800 villagers | 4 | 17ms | ~4x faster |
| Entity ticks | 3,900 villagers | 4 | 53ms | ~4x faster |
| Entity ticks | 3,900 villagers | 8 | 20ms | ~8x faster |
| Entity ticks | 5,100 villagers | 8 | 34ms | ~8x faster |
| Scheduled ticks | Redstone circuit | 8 | <1ms | Correctness validated |
| Scheduled ticks | 5000+ fluid ticks (chunk gen) | 8 | <2ms | Parallel group re-run |
| Merge | 5000 ticks, millions of reads | 1 | 0.23% of tick | 7x faster after index optimization |
| Multiplayer | 10 players, 2700 chunks, 750 entities | 8 | ~1ms entity, ~0.6ms random | Zero conflicts |

---

## Testing Checklist

### Behavioral Parity
- [ ] Run vanilla server and Mica server side by side with same seed
- [ ] Compare crop growth rates over extended period
- [ ] Compare ice/snow formation patterns
- [ ] Verify leaf decay drops items correctly
- [ ] Verify fire spread behavior
- [ ] Test with common plugins (EssentialsX, WorldGuard, etc.)

### Stress Testing
- [x] random_tick_speed 100 — passed
- [x] random_tick_speed 100000 — passed (9.5M reads, stable)
- [x] 1,800 villagers parallel entity ticking — 17ms on 4 threads
- [x] 5,235 villagers stable at 38ms on 8 threads with entity snapshots
- [x] Redstone clock with Alternate Current — cascades propagate, conflicts detected
- [x] Redstone clock with Vanilla evaluator — ThreadLocal shouldSignal fix
- [x] Cascade conflict detection (touched positions) — working
- [x] Serial fallback on conflict — identical to vanilla execution
- [x] Independent tick grouping — clean/tainted separation working
- [x] Parallel tainted group re-run — 20+ independent water pools running in parallel
- [x] OverlayMerger optimized — pre-indexed position lookups, 7x faster merge
- [x] Chunk generation with massive fluid ticks — 5000+ ticks, 276 groups, TPS 19.9
- [x] Multiple players in different areas — 4 bots at ±500 blocks, 5 min, zero conflicts
- [x] Multiple players in same area — 4 bots clustered at 5-block spacing, 5 min, zero conflicts
- [x] 10 simultaneous players, 2700+ chunks, 750 entities, zero exceptions
- [x] 4-bit full adder carry chain propagation — verified 5+3=8
- [x] Water flow with onPlace fix — downward and lateral flow correct
- [ ] Large redstone machines (100+ ticks per game tick)
- [ ] 2,000+ entities sustained without crashes (long duration)
- [ ] Lava flow (slower tick rate than water, same pipeline)

### Benchmarks (Mica vs Stock Paper)
- [ ] Chunky pre-render: compare TPS during world generation (e.g. 1000 chunk radius)
- [ ] Chunky pre-render: compare total generation time
- [ ] Entity scaling: spawn villagers in increments of 500, compare MSPT at each step
- [ ] Redstone: large piston door or sorting system, compare MSPT
- [ ] Spark profile comparison: same workload on Mica vs Paper, compare flame graphs

### Edge Cases
- [ ] Server shutdown during parallel phase
- [x] Chunk unload during parallel phase — entity onRemove deferral prevents crash
- [ ] Player disconnect during parallel phase
- [ ] World border changes during parallel phase
- [ ] Dimension changes during parallel phase
