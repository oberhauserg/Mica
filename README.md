# Mica

**Parallel tick processing for Minecraft servers, built on [Paper](https://papermc.io).**

Mica adds transparent multi-threaded tick execution to the Minecraft server using a Snapshot-Compute-Merge-Converge (SCMC) architecture. Unlike [Folia](https://papermc.io/software/folia), which splits the world into independent regions that cannot interact, Mica maintains a single unified world where all game systems can read the same global state. Parallelism comes from snapshot isolation, not spatial partitioning.

> **Status: Experimental.** Random ticks, entity ticks, and scheduled block/fluid ticks are parallelized. Redstone circuits work correctly with all three Paper evaluators (Vanilla, Eigencraft, Alternate Current). Not ready for production use.

## How It Works

Every Minecraft server tick (50ms), the server processes block updates, entity AI, mob spawning, crop growth, redstone, fluid flow, and more, all on a single thread. Mica changes this:

### The SCMC Cycle

1. **Snapshot** - At tick start, capture an immutable view of the world state. This is O(1) using copy-on-write references to chunk section arrays - no deep copy.

2. **Compute** - Dispatch tick operations across a thread pool. Each worker thread gets a *tracked overlay* - a read-write layer on top of the snapshot. Game code calls `level.getBlockState()` and `level.setBlock()` as usual, but these calls are transparently redirected to the overlay via thread-local interception. Reads check the overlay first, then fall through to the snapshot. Writes go into the overlay only.

3. **Merge** - Collect all overlays. Most writes don't conflict (different positions). For conflicts, walk the provenance graph to identify exactly which operations are affected, and re-run only those operations serially.

4. **Apply** - Commit merged writes to the live world. Replay deferred side effects (entity spawns, Bukkit events) on the main thread.

### Why Not Regions?

Folia splits the world into spatial regions that tick independently. This works well for spread-out players but has tradeoffs:
- Redstone, entities, and hoppers can't cross region boundaries without async scheduling
- Every plugin must be rewritten for region-aware execution
- Players in the same area share a single region (no parallelism benefit)

Mica takes a different approach: all workers see the same global snapshot, so there are no boundaries. A redstone wire can span any distance. A hopper can pull from any chest. Plugins call `level.getBlockState()` and get a consistent answer regardless of which thread they're on. Conflicts are rare and handled automatically by the merge phase.

### The Provenance Graph

When two workers' overlays conflict (writing to the same position, or one reading a position the other wrote), Mica doesn't throw away all work. Each overlay tracks which operation caused each read and write. The merge phase walks this provenance graph to find exactly the affected operations, discards only their results, and re-runs them serially. Everything else keeps its parallel results.

For cascading systems like redstone, Mica also tracks "touched positions", positions read during a neighbor update cascade triggered by a write. Even if the value didn't change, the position was causally influenced. If another thread read that position, a conflict is detected.

After conflict detection, Mica groups ticks into independent connected components using Union-Find. Only tainted components are re-run. Clean components keep their parallel results. When multiple tainted components exist (e.g., 20 separate water pools flowing during chunk generation), they are re-run **in parallel with each other**, each group gets its own overlay and runs on a worker thread. This means even worst-case scenarios with many conflicts can still benefit from parallelism. Two independent redstone machines, or 20 separate lava flows, all execute concurrently.

In practice, conflicts are rare for random ticks and entity ticks. Empirical data from live testing shows random ticks have a conflict rate of effectively 0% even under extreme stress (100,000x tick speed, 9.5 million reads per interval). Scheduled ticks with interacting redstone circuits produce conflicts that are detected and resolved automatically.

## Current State

### What's Parallelized
- **Random block ticks** (crop growth, ice/snow formation, fire spread, leaf decay, etc.) - dispatched across a configurable thread pool with per-thread overlays
- **Entity ticking** (AI, pathfinding, movement, collision) - 5,000+ villagers at 34ms on 8 threads with entity position snapshots for cross-entity read safety
- **Scheduled block ticks** (redstone repeaters, comparators, gravity blocks) - with cascade conflict detection and independent tick grouping via Union-Find
- **Scheduled fluid ticks** (water and lava flow)

### What's Deferred
- **Entity spawns** during parallel ticks (e.g., item drops from leaf decay) are buffered and applied on the main thread after the parallel phase
- **Bukkit events** that require the main thread (BlockSpreadEvent, BlockGrowEvent, etc.) are buffered and replayed
- **Entity chunk moves** during parallel entity ticks are deferred to prevent concurrent modification of chunk entity lists
- **Block entity creation/removal** during parallel block ticks
- **Scheduled ticks** (scheduleTick calls during parallel phase) - tagged with source operation, replayed per-group after merge

### What's Not Yet Parallelized
- Block entity ticking (hoppers, furnaces)
- Block events (pistons)

## Architecture

```
net.minecraft.server.level.mica/
├── WorldSnapshot.java        # Immutable world view at tick start (COW chunk sections)
├── TrackedOverlay.java        # Per-thread read-write layer with provenance tracking
├── OperationId.java           # Unique identifier for each tick operation
├── OperationType.java         # Enum of operation types (block tick, entity tick, etc.)
├── OverlayMerger.java         # Pre-indexed conflict detection via provenance graph walking
├── TickGrouper.java           # Union-Find grouping of independent tick components + parallel re-run
├── MicaTickEngine.java        # SCMC cycle orchestrator
├── TickOperation.java         # Operation wrappers (BlockTickOp, EntityTickOp, etc.)
├── OperationExecutor.java     # Functional interface for executing operations
└── DeferredEffects.java       # Buffers entity spawns, events, scheduled ticks for main thread replay
```

Key integration points in the server:
- `LevelChunk.java` - Overlay intercept at chunk level (transparent to game logic above)
- `Level.java` - Thread-local intercepts on `getBlockState()`, `getFluidState()`; suppresses packets/rendering in `notifyAndUpdatePhysics()` during parallel phase
- `Entity.java` - Position/bounding box snapshots for cross-entity read safety
- `ServerChunkCache.java` - Parallel dispatch in `iterateTickingChunksFaster()`; thread pool and logger
- `ServerLevel.java` - Entity tick parallelism, scheduled tick parallelism (`micaRunScheduledTicks`)
- `LevelTicks.java` - `collectForParallelDispatch()` / `finishParallelDispatch()` for tick collection
- `EntityLookup.java` - Entity chunk movement and removal deferral during parallel phase
- `OverlayMerger.java` - Pre-indexed position→reader and operation→position maps for O(1) conflict detection
- `LevelHelper.java` - Alternate Current wire state overlay intercept
- `RedStoneWireBlock.java` - ThreadLocal `shouldSignal` for vanilla redstone evaluator safety
- `PaperEventManager.java` - Sync event deferral during parallel execution
- `AsyncCatcher.java` / `TickThread.java` - Worker thread bypass during parallel execution

## Comparison

| | Paper | Folia | Mica |
|---|---|---|---|
| Threading model | Single-threaded tick | Regionized parallel | Snapshot-isolated parallel |
| World model | One world, one thread | Independent regions | One world, parallel reads |
| Plugin compatibility | Full | Requires rewrite | Mostly compatible* |
| Cross-region interaction | N/A | Async scheduling required | Transparent (snapshot) |
| Redstone correctness | Full | Per-region only | Full (conflict detection + serial fallback) |
| Entity scaling | ~200 villagers/tick | Per-region | 5,000+ villagers on 8 threads |
| Conflict handling | N/A | Forbidden | Automatic (provenance graph + Union-Find grouping) |

*Cancellable Bukkit events during parallel execution are currently deferred - see HARDENING.md

## Roadmap

- [x] Core SCMC infrastructure (snapshot, overlay, merge, provenance tracking)
- [x] Thread-local world access interception
- [x] Parallel random chunk ticks
- [x] Deferred entity spawns and Bukkit events
- [x] Parallel entity ticking with position snapshots
- [x] Parallel scheduled block and fluid ticks
- [x] Cascade conflict detection (touched positions)
- [x] Independent tick grouping (Union-Find connected components)
- [x] Per-thread Alternate Current WireHandler
- [x] Vanilla/Eigencraft redstone evaluator safety (ThreadLocal shouldSignal)
- [x] Transparent overlay at LevelChunk level (vanilla game logic runs naturally)
- [x] Full conflict detection with provenance graph walking
- [x] Parallel re-run of independent tainted groups
- [x] Pre-indexed merge and grouper (sub-millisecond with thousands of ticks)
- [x] Entity removal deferral for chunk unload safety
- [x] willTickThisTick correctness (markTickExecuted matching vanilla behavior)
- [x] 10-player multiplayer stress test
- [x] 4-bit adder timing verification
- [ ] Parallel block entity ticking (hoppers, furnaces)
- [ ] Buffered block update packets to eliminate flicker
- [ ] Cancellable Bukkit event support during parallel execution
- [ ] Configurable thread count and subsystem opt-in
- [ ] Performance benchmarking vs Paper and Folia
- [ ] Plugin compatibility testing

## Theoretical Foundations

Mica draws on well-established concepts from several fields:

- **Software Transactional Memory (STM)** - Workers execute optimistically, conflicts are detected at commit time and resolved by retry. No locks, no deadlocks.
- **Snapshot Isolation (databases)** - Readers see a frozen point-in-time view. Our provenance graph walking implements serializable snapshot isolation (SSI) to detect and fix anomalies.
- **Domain Decomposition (scientific computing)** - Folia's approach. We chose task-based parallelism over spatial decomposition, but the boundary/halo concepts informed our design.
- **Entity Component Systems (game engines)** - Systems declare read/write access, scheduler parallelizes non-overlapping systems. Our overlay tracking achieves the same result dynamically at runtime.

---

## How To (Server Admins)

Download or build the Mica jar and run it just like a Paper server.

Mica logs parallel tick stats every 200 ticks:
```
[Mica] 8 threads | 441 chunks | 98700246 reads, 80 writes | 0 conflicts, 0 tainted, 0 re-runs
[Mica] Timing: 36.38ms total | snapshot 0.04ms | compute 36.14ms | merge 0.02ms | converge 0.00ms | apply 0.18ms
[Mica] [Entities] 34.12ms | 8 threads | 5104 entities | 133583 reads, 0 writes, 0 deferred
[Mica] [Mica/Scheduled] 4 groups (3 clean, 1 tainted) from 5 ticks
```

* Paper documentation applies to Mica: [docs.papermc.io](https://docs.papermc.io)

## How To (Plugin Developers)

Mica is API-compatible with Paper. Existing Paper plugins should work without modification in most cases. See [HARDENING.md](HARDENING.md) for known edge cases around event timing during parallel ticks.

* See the Paper API [here](paper-api)
* Paper API javadocs: [papermc.io/javadocs](https://papermc.io/javadocs/)

#### Repository (for paper-api)
##### Maven

```xml
<repository>
    <id>papermc</id>
    <url>https://repo.papermc.io/repository/maven-public/</url>
</repository>
```

```xml
<dependency>
    <groupId>io.papermc.paper</groupId>
    <artifactId>paper-api</artifactId>
    <version>1.21.11-R0.1-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```
##### Gradle
```kotlin
repositories {
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
```

## How To (Compiling From Source)

Requires JDK 21 and an internet connection.

```bash
git clone https://github.com/oberhauserg/Mica.git
cd Mica
./gradlew applyPatches
./gradlew createMojmapBundlerJar
```

The server jar will be in `paper-server/build/libs/`.

To get a full list of tasks, run `./gradlew tasks`.

## How To (Contributing)

See [Contributing](CONTRIBUTING.md) and [HARDENING.md](HARDENING.md) for current priorities.

## Old Versions (1.21.3 and below)

For branches of Paper versions 1.8-1.21.3, please see Paper's [archive repository](https://github.com/PaperMC/Paper-archive).

## License

Inherits Paper's license. See [LICENSE.md](LICENSE.md).

## Acknowledgements

Built on [PaperMC](https://papermc.io) and the work of the Paper team, especially Spottedleaf's chunk system and Starlight lighting engine.
