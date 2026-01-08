# DeerFolia Copilot Instructions

## Project Overview
DeerFolia is a **Minecraft server fork** based on Folia (which itself forks Paper → Spigot → CraftBukkit → Mojang's vanilla server). It restores vanilla mechanics (like sand duplication) and adds performance optimizations (DAB, async pathfinding, entity throttling) while maintaining minimal modifications to upstream.

## Architecture: Patch-Based Development
This is a **paperweight patcher project** - NOT a traditional Java project:
- Source code lives in **patch files** (`*.patch`), not directly in source directories
- `DeerFolia-server/` and `DeerFolia-api/` are **generated** by applying patches
- The upstream hierarchy: `Mojang → Paper → Folia → DeerFolia`

### Patch Locations
| Directory | Purpose |
|-----------|---------|
| `DeerFolia-server/minecraft-patches/features/` | Core server modifications to Minecraft/Folia code |
| `DeerFolia-server/paper-patches/features/` | Modifications to Paper's layer |
| `DeerFolia-server/src/main/java/cn/lunadeer/mc/deerfolia/` | **New DeerFolia-specific classes** (no patches needed) |

## Essential Commands
```bash
./gradlew applyAllPatches       # Apply patches to generate source (REQUIRED before coding)
./gradlew rebuildAllServerPatches  # Generate patches from committed changes
./gradlew createMojmapPaperclipJar # Build server JAR → DeerFolia-server/build/libs/
```

## Code Modification Workflow
**Modifying upstream code (Minecraft/Paper/Folia):**
1. Edit files in `DeerFolia-server/` or `DeerFolia-api/`
2. Stage and commit: `git add . && git commit -m "Description"`
3. Run `./gradlew rebuildAllServerPatches` to generate patch files

**Adding new classes:** Place in `DeerFolia-server/src/main/java/cn/lunadeer/mc/deerfolia/` - no patch needed.

## Code Marking Convention (CRITICAL)
All modifications to upstream files MUST be marked:
```java
// Single line change:
entity.doSomething(); // DeerFolia - description

// Multi-line block:
// DeerFolia start - description
modifiedCode();
moreChanges();
// DeerFolia end - description
```

## Configuration System
- Config class: [DeerFoliaConfiguration.java](DeerFolia-server/src/main/java/cn/lunadeer/mc/deerfolia/DeerFoliaConfiguration.java)
- Runtime file: `config/deer-folia.yml`
- Add options using `@Comments` annotation and `ConfigurationPart` for nested configs
- Access: `cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration.optionName`

## Import Conventions
When modifying upstream files, use **fully-qualified class names** instead of adding imports to avoid patch conflicts:
```java
// Preferred in upstream modifications:
org.bukkit.Location loc = new org.bukkit.Location(...);

// Only for new DeerFolia classes:
import org.bukkit.Location;
```

## Build Requirements
- Java 21+ (toolchain managed by Gradle)
- Upstream ref tracked in `gradle.properties` → `foliaRef`
