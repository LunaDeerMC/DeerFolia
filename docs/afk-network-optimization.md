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
  afk-enter-message: "&7[AFKNet] &eYou are now AFK. &7DeerFolia will temporarily suppress non-essential packets to save bandwidth."
  afk-exit-message: "&7[AFKNet] &aWelcome back! &7During this AFK session, you saved &b{saved_traffic} &7of traffic and up to &b{saved_bandwidth} &7of bandwidth. &aThanks for helping the server."
  count-look-changes-as-activity: true
  resync-on-resume: true
  stats-enabled: true
  suppression-whitelist-categories: []
  max-suppressed-bytes-before-category-resync: -1
```

### Key Options

- `afk-enter-message`: system message sent once when the player first becomes AFK; leave empty to disable; supports `{player}` plus legacy color codes like `&7`, `&a`, and hex colors like `&#55FFFF`
- `afk-exit-message`: system message sent when the player becomes active again; leave empty to disable; supports `{player}`, `{saved_traffic}`, and `{saved_bandwidth}` using **this AFK session's** saved traffic and peak saved bandwidth; also supports the same color syntax
- `suppression-whitelist-categories`: categories that should remain unsuppressed even while AFK
- `max-suppressed-bytes-before-category-resync`: per-player, per-category byte threshold before a category refresh is forced; `-1` means unlimited suppression until the player becomes active
- `stats-enabled`: controls `/afknetstats` accumulation only; AFK session savings used by the exit message are still tracked so the notification can report meaningful values

## Statistics Command

```text
/afknetstats
/afknetstats <player>
```

The command reports total saved traffic, saved traffic grouped by suppressible category, and peak saved bandwidth.
