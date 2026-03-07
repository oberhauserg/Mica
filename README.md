# Mica

**Parallel tick processing for Minecraft servers, built on [Paper](https://papermc.io).**

Mica adds transparent multi-threaded tick execution to the Minecraft server using a Snapshot-Compute-Merge-Converge (SCMC) architecture. Unlike [Folia](https://papermc.io/software/folia), which splits the world into independent regions that cannot interact, Mica maintains a single unified world where all game systems can read the same global state. Parallelism comes from snapshot isolation, not spatial partitioning.

> **Status: Early experimental.** Random chunk ticks are parallelized. Entity ticking, block entity ticking, and scheduled ticks are not yet parallelized. Not ready for production use.

## How It Works

Every Minecraft server tick (50ms), the server processes block updates, entity AI, mob spawning, crop growth, redstone, fluid flow, and more all on a single thread. Mica changes this:

### The SCMC Cycle

1. **Snapshot** - At tick start, capture an immutable view of the world state. This is O(1) using copy-on-write references to chunk section arrays.

2. **Compute** - Dispatch tick operations across a thread pool. Each worker thread gets a *tracked overlay*, a read-write layer on top of the snapshot. Game code calls `level.getBlockState()` and `level.setBlock()` as usual, but these calls are transparently redirected to the overlay via thread-local interception. Reads check the overlay first, then fall through to the snapshot. Writes go into the overlay only.

3. **Merge** - Collect all overlays. Most writes don't conflict (different positions). For conflicts, walk the provenance graph to identify exactly which operations are affected, and re-run only those operations serially.

4. **Converge** - Commit merged writes to the live world. Replay deferred side effects (entity spawns, Bukkit events) on the main thread.

### Why Not Regions?

Folia splits the world into spatial regions that tick independently. This works well for spread-out players but has tradeoffs:
- Redstone, entities, and hoppers can't cross region boundaries without async scheduling
- Every plugin must be rewritten for region-aware execution
- Players in the same area share a single region (no parallelism benefit)

Mica takes a different approach: all workers see the same global snapshot, so there are no boundaries. A redstone wire can span any distance. A hopper can pull from any chest. Plugins call `level.getBlockState()` and get a consistent answer regardless of which thread they're on. Conflicts are rare and handled automatically by the merge phase.

### The Provenance Graph

When two workers' overlays conflict (writing to the same position, or one reading a position the other wrote), Mica doesn't throw away all work. Each overlay tracks which operation caused each read and write. The merge phase walks this provenance graph to find exactly the affected operations, discards only their results, and re-runs them serially. Everything else keeps its parallel results.

In practice, conflicts are extremely rare. Empirical data from live testing shows random ticks have a conflict rate of effectively 0% even under extreme stress (100,000x tick speed, 9.5 million reads per interval).

## Current State

### What's Parallelized
- **Random block ticks** (crop growth, ice/snow formation, fire spread, leaf decay, etc.) - dispatched across a configurable thread pool with per-thread overlays

### What's Deferred
- **Entity spawns** during parallel ticks (e.g., item drops from leaf decay) are buffered and applied on the main thread after the parallel phase
- **Bukkit events** that require the main thread (BlockSpreadEvent, BlockGrowEvent, etc.) are buffered and replayed

### What's Not Yet Parallelized
- Entity ticking (AI, pathfinding, movement) - the biggest performance target
- Block entity ticking (hoppers, furnaces)
- Scheduled block ticks (redstone, fluid flow)
- Block events (pistons)

## Architecture

```
net.minecraft.server.level.mica/
├── WorldSnapshot.java        # Immutable world view at tick start (COW chunk sections)
├── TrackedOverlay.java        # Per-thread read-write layer with provenance tracking
├── OperationId.java           # Unique identifier for each tick operation
├── OperationType.java         # Enum of operation types (block tick, entity tick, etc.)
├── OverlayMerger.java         # Conflict detection via provenance graph walking
├── MicaTickEngine.java        # SCMC cycle orchestrator
├── TickOperation.java         # Operation wrappers (BlockTickOp, EntityTickOp, etc.)
├── OperationExecutor.java     # Functional interface for executing operations
└── DeferredEffects.java       # Buffers entity spawns, events for main thread replay
```

Key integration points in the server:
- `Level.java` - Thread-local intercepts on `getBlockState()`, `getFluidState()`, `setBlock()`
- `ServerChunkCache.java` - Parallel dispatch in `iterateTickingChunksFaster()`
- `PaperEventManager.java` - Sync event deferral during parallel execution
- `AsyncCatcher.java` - Worker thread bypass during parallel execution
- `ServerLevel.java` - Entity spawn deferral during parallel execution

## Comparison

| | Paper | Folia | Mica |
|---|---|---|---|
| Threading model | Single-threaded tick | Regionized parallel | Snapshot-isolated parallel |
| World model | One world, one thread | Independent regions | One world, parallel reads |
| Plugin compatibility | Full | Requires rewrite | Mostly compatible |
| Cross-region interaction | N/A | Async scheduling required | Transparent (snapshot) |
| Best for | General use | Spread-out players | Any server with tick pressure |
| Conflict handling | N/A | Forbidden | Automatic (provenance graph) |

## Roadmap

- [x] Core SCMC infrastructure (snapshot, overlay, merge, provenance tracking)
- [x] Thread-local world access interception
- [x] Parallel random chunk ticks
- [x] Deferred entity spawns and Bukkit events
- [ ] Parallel entity ticking
- [ ] Parallel block entity ticking (hoppers, furnaces)
- [ ] Parallel scheduled ticks (redstone, fluids)
- [ ] Full conflict detection with provenance graph walking
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
[Mica] Parallel random ticks: 4 threads, 441 chunks, 14199 reads, 26 writes, 26 deferred effects
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

Special thanks to:

[![YourKit-Logo](https://www.yourkit.com/images/yklogo.png)](https://www.yourkit.com/)

[YourKit](https://www.yourkit.com/), makers of the outstanding java profiler, support open source projects of all kinds with their full featured [Java](https://www.yourkit.com/java/profiler) and [.NET](https://www.yourkit.com/.net/profiler) application profilers. We thank them for granting Paper an OSS license so that we can make our software the best it can be.

[<img src="https://user-images.githubusercontent.com/21148213/121807008-8ffc6700-cc52-11eb-96a7-2f6f260f8fda.png" alt="" width="150">](https://www.jetbrains.com)

[JetBrains](https://www.jetbrains.com/), creators of the IntelliJ IDEA, supports Paper with one of their [Open Source Licenses](https://www.jetbrains.com/opensource/). IntelliJ IDEA is the recommended IDE for working with Paper, and most of the Paper team uses it.

All our sponsors!
[![Sponsor Image](https://raw.githubusercontent.com/PaperMC/papermc.io/data/sponsors.png)](https://papermc.io/sponsors)
