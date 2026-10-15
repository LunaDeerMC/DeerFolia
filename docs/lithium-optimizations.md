# Lithium optimizations

DeerFolia ports server-side optimizations from
[CaffeineMC Lithium](https://github.com/CaffeineMC/lithium) and adapts them to
Paper's patches, Moonrise, and Folia's region-threaded execution model.

These are selected ports, not a complete Lithium implementation. The switches
below control only the optimizations included in DeerFolia. See the
[migration value analysis](lithium-migration-analysis.md) for a mechanism-level
comparison, remaining candidates, priorities, and Folia/Bukkit constraints.

The original set of ports was based on Lithium `mc26.2-0.25.2`, commit
`8940fbcb39bac29b7a3dc279fa61595cd9dc4deb`, adapted to the Minecraft 26.3
Folia base. Lithium is licensed under the
GNU Lesser General Public License version 3; a copy is included at
`resources/licenses/Lithium-LGPL-3.0.txt`.

## Configuration

The generated `config/deer-folia.yml` contains:

```yaml
lithium-optimize:
  enabled: true
  allocations: true
  ai: true
  collections: true
  entity: true
  math: true
  world: true
  block-effects: true
  heightmaps: true
  game-events: true
  brain-tasks: true
  brain-memory: true
  block-search: true
  block-entity-sleeping: true
```

Changes to these options require a server restart. `enabled` is the master
switch. `block-effects` also requires `entity`; `heightmaps`, `game-events`
and `block-entity-sleeping` require `world`; the three Brain/search options
require `ai`. Each new mechanism can be disabled independently.

## Additional mechanisms adapted to DeerFolia

These implementations follow the optimization ideas and current server semantics,
rather than copying a particular Lithium release. Validation and measured limits
are recorded in [the first-batch implementation report](lithium-phase1-results.md).

- Block-effect collectors allocate their collections only when needed and skip
  empty steps while preserving effect order and source positions.
- The four block-update heightmaps share a downward scan, with independent
  predicates and synchronized serialized/raw height storage.
- Game events query existing section listener registries without creating empty
  registries; Bukkit cancellation, radius changes and region checks remain active.
- Brain traversal caches task groups and reuses running snapshots. A flat status
  scan remains for custom controls and external status changes. Immutable memory
  prerequisites in `Behavior` use a per-Brain presence version; mutable conditions
  and overridden memory checks use the normal checks.
- Native turtle-egg searches skip block checks in sections whose palettes cannot
  contain the target. The original coordinate order and final checks remain.
- Crafters with no countdown, idle campfires, fully closed shulker boxes and
  redstone-locked hoppers can skip their idle work. Ticker identity and lifecycle
  checks remain; hoppers continue advancing their cooldown and region time.
  State changes, loading, relevant Bukkit live setters and ticker rebinding wake
  the block entity. Sleeping entries still participate in the region ticker loop
  and its mid-tick task cadence, but do not enter per-block-entity profiler timers;
  the outer region block-entity timer still includes their validation and clock work.
  This is not full inventory-driven hopper sleeping.

## DeerFolia-specific ports

- AI behavior gates check running behaviors in the same pass that ticks them.
- Tempting sensors find the nearest local-region player without stream sorting
  and retain Bukkit target events.
- Deep passenger traversal avoids recursively composed streams.
- Piston and redstone hot paths reuse a cached direction array.
- Entity attribute synchronization uses identity-based fastutil sets.
- Inactive glide updates return before expensive equipment or attribute work.
- Frozen entities avoid a legacy block-state lookup until frost handling is
  actually required.
- Dedicated servers skip client-only sprint particle work.
- Entity explosion resistance avoids lambda and redundant `Optional`
  allocation.
- Raid boss-bar health recalculation is coalesced to at most once per tick.
- Composter sided inventories reuse immutable-by-convention slot arrays.
- Axis cycling and AABB min/max access use direct branches.
- Noise chunk generators cache their sea level after registry binding.
- Dry weather precipitation ticks return before biome work when neither ice nor
  snow can form.
- Freeze checks reuse an already fetched block state's fluid state.
- Fluid spreading evaluates cheap rejection checks first.

Minecraft 26.3 replaced the old swing state and fluid block allowlist. The port
uses those upstream implementations instead of restoring the removed swing
method or replacing the new `WASHED_AWAY_BY_FLUIDS` tag semantics.

## Optimizations already supplied by the base

Paper, Folia, and Moonrise already implement optimizations in several of the
same areas. This overlap does not establish complete equivalence with each
Lithium module. Existing mechanisms in the current 26.3 base include:

- fastutil-backed NBT compounds and entity tracker sets;
- cached fluid-state properties;
- palette, bit-storage, chunk ticking, POI, and entity lookup optimizations;
- collision, voxel-shape, explosion block-cache, and block-state caches;
- optimized hoppers and redstone implementations;
- stream-free pathfinder and AI gate start policies.

Client-only mixins are not applicable to a dedicated server. Lithium's profiler
two-thread cache is also not copied because Folia can have many concurrently
active region tick threads. Optimizations which rely on a single mutable
per-level position or which scan/load chunks outside the owning region are also
excluded because those assumptions are incompatible with Folia's region
threading.
