# Mica Hardening Checklist

Issues discovered during Phase 1 implementation that need proper solutions
before expanding to entity ticking.

## Thread Safety Bypasses (Currently Hacked)

### 1. AsyncCatcher bypass
**File:** `AsyncCatcher.java`

**Current fix:** Blanket bypass for all ForkJoinWorkerThread calls

**Proper fix:** Only bypass for operations we've explicitly determined are safe
during parallel execution. Each call site that hits AsyncCatcher needs to be
categorized:
- Entity add → buffer in DeferredEffects (partially done)
- Entity remove → buffer in DeferredEffects (not done)
- Chunk operations → evaluate if safe or needs deferral
- Scoreboard operations → likely needs deferral

### 2. PaperEventManager sync event bypass
**File:** `PaperEventManager.java`

**Current fix:** Silently buffer all sync events from ForkJoinWorkerThreads

**Proper fix:** Categorize events:
- **Safe to defer:** BlockGrowEvent, BlockSpreadEvent, BlockFormEvent,
  BlockFadeEvent — these are informational, plugins can't cancel them
  meaningfully during parallel execution anyway
- **Need cancel support:** Some events return cancel state that affects
  game logic (e.g., BlockSpreadEvent.isCancelled() prevents vine spread).
  If we defer these, we lose the ability to cancel. Options:
    - Run the event synchronously (defeats parallelism for that operation)
    - Always allow (ignore plugin cancellation during parallel ticks)
    - Pre-check: query plugin listeners before parallel phase to see if
      anyone is listening, skip deferral if not
- **Document:** Which events may behave differently under Mica

### 3. Entity spawns from parallel threads
**File:** `ServerLevel.addEntity()`

**Current fix:** Buffer in DeferredEffects, apply after parallel phase

**Proper fix:** This is actually pretty close to correct. Needs:
- Ensure entity UUID uniqueness across threads (two threads spawning
  entities simultaneously could collide)
- Ensure entity position is still valid when deferred spawn executes
  (block may have changed between buffer and apply)
- Handle spawn reason correctly for all code paths that call addEntity

## World Access Gaps

### 4. Direct chunk access bypassing Level.getBlockState()
**Risk:** Code that calls `chunk.getBlockState(pos)` directly instead of
`level.getBlockState(pos)` will bypass our overlay intercept.

**Action items:**
- Audit all call sites in `optimiseRandomTick()` — Paper's optimized
  random tick code reads section palettes directly
- Audit `tickChunk()` — ice/snow/thunder code
- For Phase 1 (random ticks only) this is low risk since most random tick
  block implementations go through Level
- For entity ticking this becomes critical — pathfinding, collision detection,
  and sensing all have optimized paths

### 5. Block entity state not snapshotted
**Current:** `TrackedOverlay.getBlockEntity()` falls through to live world

**Risk:** If parallel tick reads a hopper's inventory while another thread
modifies it, we get inconsistent state

**For random ticks:** Low risk (random ticks rarely read block entities)

**For entity/block entity ticking:** Must snapshot or synchronize

### 6. Entity state not snapshotted
**Current:** Entity positions, health, AI state are all live mutable state

**Risk:** Two threads reading the same entity's position could get different
values if one thread is also moving it

**For random ticks:** Not an issue (random ticks don't read entity state
beyond what's captured in the snapshot)

**For entity ticking:** This is the core challenge. Options:
- Snapshot entity state (expensive, complex)
- Entity ticks only modify their own entity (usually true), treat as
  single-writer
- Shared entity reads (nearby entity sensing) always go through snapshot
  of positions at tick start

## Missing Intercept Points

### 7. Other write methods on Level
Currently intercepted: `setBlock`, `getBlockState`, `getFluidState`
Not yet intercepted:
- `removeBlock()` — delegates to setBlock, so it's covered
- `destroyBlock()` — delegates to setBlock, covered
- `setBlockAndUpdate()` — delegates to setBlock, covered
- `addFreshEntity()` → already buffered in DeferredEffects
- `neighborChanged()` — the recursive update system. NOT intercepted.
  During parallel execution, if a block change triggers neighborChanged,
  it will propagate through the live world, not the overlay.
  **This is the biggest gap for scheduled tick parallelization.**
- `updateNeighborsAt()` — same issue
- `blockEvent()` — adds to block event queue, not thread-safe
- `playSound()` / `sendParticles()` — side effects, should buffer
- `gameEvent()` — sculk sensors, should buffer

### 8. Neighbor updates during setBlock
**Current:** Our setBlock intercept returns true without calling
`notifyAndUpdatePhysics()`. This means:
- No neighbor notifications during parallel phase
- No client updates during parallel phase
- No physics propagation during parallel phase
  **This is intentional for Phase 1** but needs to be handled for
  scheduled ticks where neighborChanged cascades are the primary mechanism.

## Performance Concerns

### 9. ThreadLocal overhead
Every `getBlockState()` call now does a `ThreadLocal.get()`. At millions
of calls per tick, this adds up.

**Measure:** Profile the overhead with and without the ThreadLocal check.

**Optimize if needed:** Use a boolean flag instead of null check, or use
thread ID comparison instead of ThreadLocal.

### 10. Overlay memory allocation
Currently creating new TrackedOverlay objects each tick. The internal
HashMaps grow and get GC'd.

**Optimize:** Pool overlays, reuse HashMap capacity across ticks.

### 11. Snapshot capture time
`WorldSnapshot.capture()` iterates all ticking chunks and copies section
array references. Should be fast but hasn't been measured.

**Measure:** Time the capture phase separately in the stats logging.

## Testing Needed

### 12. Behavioral parity
- [ ] Run vanilla server and Mica server side by side with same seed
- [ ] Compare crop growth rates over extended period
- [ ] Compare ice/snow formation patterns
- [ ] Verify leaf decay drops items correctly
- [ ] Verify fire spread behavior
- [ ] Test with common plugins (EssentialsX, WorldGuard, etc.)

### 13. Stress testing
- [x] random_tick_speed 100 — passed
- [x] random_tick_speed 100000 — passed (9.5M reads, stable)
- [ ] Multiple players in different areas
- [ ] Multiple players in same area
- [ ] Large redstone machines running during parallel ticks
- [ ] Hopper-heavy chunk configurations

### 14. Edge cases
- [ ] Server shutdown during parallel phase
- [ ] Chunk unload during parallel phase
- [ ] Player disconnect during parallel phase
- [ ] World border changes during parallel phase
- [ ] Dimension changes during parallel phase
