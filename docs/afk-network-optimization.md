# AFK Network Optimization

## Overview

This feature reduces downstream traffic for inactive players. When a player is considered AFK, DeerFolia suppresses selected clientbound packet categories for that player. When the player becomes active again, the server performs a resynchronization so the client can catch up safely.

## Suppressible Categories

These are the categories exposed to users for statistics and whitelist configuration:

| Category | Typical packets |
| --- | --- |
| `chunk-stream` | chunk data, light updates, chunk batch packets |
| `block-updates` | block updates, multi-block updates, block entity updates |
| `entity-stream` | entity add/remove, movement, metadata, attributes, equipment |
| `world-effects` | sounds, particles, level events, explosions |
| `ui-stream` | title/actionbar, bossbar, tablist, scoreboard, map, advancements |

Internal must-pass packets are not exposed as configurable categories.

## Configuration

```yaml
afk-network-optimization:
  enabled: true
  afk-threshold-ticks: 1200
  count-look-changes-as-activity: true
  resync-on-resume: true
  stats-enabled: true
  suppression-whitelist-categories: []
  max-suppressed-bytes-before-category-resync: -1
```

### Key Options

- `suppression-whitelist-categories`: categories that should remain unsuppressed even while AFK
- `max-suppressed-bytes-before-category-resync`: per-player, per-category byte threshold before a category refresh is forced; `-1` means unlimited suppression until the player becomes active

## Statistics Command

```text
/afknetstats
/afknetstats <player>
```

The command reports total saved traffic, saved traffic grouped by suppressible category, and peak saved bandwidth.
