# Lithium optimizations

DeerFolia ports server-side optimizations from
[CaffeineMC Lithium](https://github.com/CaffeineMC/lithium) and adapts them to
Paper's patches, Moonrise, and Folia's region-threaded execution model.

The current port is based on Lithium `mc26.2-0.25.2`, commit
`8940fbcb39bac29b7a3dc279fa61595cd9dc4deb`. Lithium is licensed under the
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
```

Changes to these options require a server restart. `enabled` is the master
switch; the remaining options control optimization groups.

## DeerFolia-specific ports

- AI behavior gates check running behaviors in the same pass that ticks them.
- Tempting sensors find the nearest local-region player without stream sorting
  and retain Bukkit target events.
- Deep passenger traversal avoids recursively composed streams.
- Piston and redstone hot paths reuse a cached direction array.
- Entity attribute synchronization uses identity-based fastutil sets.
- Inactive swing and glide updates return before expensive equipment or
  attribute work.
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
- Fluid spreading evaluates cheap rejection checks first and recognizes sign
  blocks without a tag lookup.

## Optimizations already supplied by the base

Paper, Folia, and Moonrise already contain many optimizations equivalent to
Lithium. DeerFolia deliberately does not duplicate those implementations. The
26.2 base already covers, among other areas:

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
