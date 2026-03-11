---
name: deerfolia-add-feature
description: Add new features or optimizations to DeerFolia server. Use when implementing new gameplay mechanics, performance optimizations, vanilla behavior restoration, or server enhancements. Covers the full workflow of adding configuration options, creating new classes, writing patches, and documenting features. Triggers on requests to add new features, implement optimizations, restore vanilla mechanics, or extend server functionality.
---

# Adding Features to DeerFolia

## Feature Types

DeerFolia features typically fall into these categories:
- **Vanilla restoration** (e.g., sand duplication, end teleportation)
- **Performance optimization** (e.g., DAB, async pathfinding, POI caching, network optimization)
- **Entity behavior tuning** (e.g., entity throttling, activation range)

## Step-by-Step Workflow

### 1. Add Configuration Option

Edit `DeerFolia-server/src/main/java/cn/lunadeer/mc/deerfolia/DeerFoliaConfiguration.java`.

**Simple boolean toggle:**
```java
@Comments("Description of the feature")
public static boolean myFeature = true;  // default value
```

**Feature with multiple options (use nested ConfigurationPart):**
```java
@Comments("Description of the feature group")
public static MyFeature myFeature = new MyFeature();

public static class MyFeature extends ConfigurationPart {
    @Comments("Enable this feature")
    public boolean enabled = true;
    @Comments("Description of this numeric option")
    public int someValue = 42;
}
```

**Available annotations:**
- `@Comments("text")` — YAML comment above the field
- `@PostProcess` — on a static method, called after config is loaded
- `@PreProcess` — on a static method, called before config is loaded
- `@HandleManually` — skip automatic loading for this field

**Config file location at runtime:** `config/deer-folia.yml`

### 2. Create New Classes (if needed)

Place new classes under: `DeerFolia-server/src/main/java/cn/lunadeer/mc/deerfolia/`

Organize by feature:
```
cn/lunadeer/mc/deerfolia/
├── DeerFoliaConfiguration.java
├── async/path/          # Async pathfinding classes
├── utils/configuration/ # Config system utilities
└── myfeature/           # Your new feature package
```

New classes use normal Java imports — no FQN requirement.

### 3. Modify Upstream Code via Patches

When the feature requires changes to Minecraft/Paper/Folia code:

1. Ensure `./gradlew applyAllPatches` has been run
2. Edit files in `DeerFolia-server/` (generated source)
3. Mark ALL changes:
   ```java
   // DeerFolia start - my feature description
   if (cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration.myFeature.enabled) {
       // optimized path
   } else {
       // original behavior
   }
   // DeerFolia end - my feature description
   ```
4. Use FQN for all DeerFolia references in upstream files
5. Commit in the subproject:
   ```bash
   cd DeerFolia-server
   git add .
   git commit -m "My feature description"
   ```
6. Rebuild patches: `./gradlew rebuildAllServerPatches`

### 4. Build and Verify

```bash
./gradlew createMojmapPaperclipJar
```

Output JAR: `DeerFolia-server/build/libs/DeerFolia-paperclip-*-mojmap.jar`

### 5. Document (optional)

Add documentation under `docs/` for complex features. See existing examples:
- `docs/network_optimizations.md`
- `docs/poi_optimizations.md`

## Existing Feature Patterns to Follow

### Pattern: Configurable optimization with distance check
From DAB (Dynamic Activation Brain) — `0004-Dynamic-Activation-Brain.patch`:
```java
// DeerFolia start - pufferfish DAB
if (cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration.dynamicActivationBrain.enabled && entity.getType().dabEnabled) {
    // Calculate distance, adjust priority
    int squaredDistance = (int) (diffX * diffX + diffY * diffY + diffZ * diffZ);
    entity.activatedPriority = squaredDistance > startDistance * startDistance ?
        Math.max(1, Math.min(squaredDistance >> activationDistanceMod, entity.activatedPriority)) : 1;
}
// DeerFolia end - pufferfish DAB
```

### Pattern: Rate-limited sensor check
From POI optimizations — `0009-POI-Optimization.patch`:
- Add tick counter field to entity
- Check `tickCount % configuredInterval == 0` before expensive operation
- Skip if no player within configured distance

### Pattern: Async offloading with virtual threads
From async pathfinding — `0005-Async-pathfinding.patch`:
- New classes in `cn/lunadeer/mc/deerfolia/async/path/`
- `AsyncPathProcessor` manages virtual thread pool
- Cache system (`NodeEvaluatorCache`) for thread safety
- State tracking (`PathProcessState`) for async results

### Pattern: Simple default value change
From sand duplication — `0003-Allow-sand-duplication.patch`:
```java
public boolean forceTickAfterTeleportToDuplicate = true;    // DeerFolia - Sand duplication
```

### Pattern: Delegating to external config
From entity throttling — `0007-Kaiiju-Entity-Throttling.patch`:
- Boolean toggle in DeerFoliaConfiguration
- `@PostProcess` method loads a separate config file
- Third-party config class handles the details

## Checklist

- [ ] Configuration option added with `@Comments`
- [ ] Default value is sensible (feature enabled by default for optimizations)
- [ ] All upstream changes marked with `// DeerFolia` comments
- [ ] FQN used for DeerFolia classes in upstream files
- [ ] Changes committed in subproject before rebuilding patches
- [ ] Patches regenerated with `./gradlew rebuildAllServerPatches`
- [ ] Build succeeds: `./gradlew createMojmapPaperclipJar`
