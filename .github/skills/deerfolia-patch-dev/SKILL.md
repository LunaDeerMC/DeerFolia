---
name: deerfolia-patch-dev
description: Develop, modify, and maintain DeerFolia server patches. Use when editing upstream Minecraft/Paper/Folia code, creating or updating patch files, fixing patch conflicts, modifying existing features in upstream files, or when user mentions patches, upstream code changes, code marking, or rebuildPatches. Covers the core patch-based development workflow for this paperweight patcher project.
---

# DeerFolia Patch Development

## Architecture

DeerFolia is a paperweight patcher project. Source lives in patch files (`*.patch`), not directly in source directories. `DeerFolia-server/` and `DeerFolia-api/` are **generated** by applying patches.

Upstream hierarchy: `Mojang → Paper → Folia → DeerFolia`

### Patch Locations

| Directory | Purpose |
|-----------|---------|
| `DeerFolia-server/minecraft-patches/features/` | Modifications to Minecraft/Folia code |
| `DeerFolia-server/paper-patches/features/` | Modifications to Paper's layer |
| `DeerFolia-api/paper-patches/` | API-layer modifications (currently empty) |
| `DeerFolia-api/folia-patches/` | Folia API modifications (currently empty) |

### Non-Patch Code

New DeerFolia-specific classes go in `DeerFolia-server/src/main/java/cn/lunadeer/mc/deerfolia/` — no patches needed.

## Essential Commands

```bash
./gradlew applyAllPatches          # Apply patches → generate source (MUST run before coding)
./gradlew rebuildAllServerPatches  # Generate patches from committed changes
./gradlew createMojmapPaperclipJar # Build server JAR
```

## Workflow: Modify Upstream Code

1. Ensure patches are applied: `./gradlew applyAllPatches`
2. Navigate to the generated source in `DeerFolia-server/` or `DeerFolia-api/`
3. Edit the target file, marking ALL changes (see marking rules below)
4. Stage and commit inside the subproject:
   ```bash
   cd DeerFolia-server
   git add .
   git commit -m "Short description of change"
   ```
5. Regenerate patches: `./gradlew rebuildAllServerPatches`
6. Verify the generated/updated `.patch` file in the appropriate patches directory
7. Build to verify: `./gradlew createMojmapPaperclipJar`

## Code Marking Rules (CRITICAL)

Every modification to upstream files MUST be marked with comments:

```java
// Single-line change:
entity.doSomething(); // DeerFolia - description

// Multi-line block:
// DeerFolia start - description
modifiedCode();
moreChanges();
// DeerFolia end - description

// Commenting out upstream code:
// DeerFolia start - description
// originalCode();
replacementCode();
// DeerFolia end - description
```

## Import Convention

In upstream file modifications, use **fully qualified class names** instead of adding imports:

```java
// CORRECT in upstream patches:
cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration.dynamicActivationBrain.enabled

// WRONG in upstream patches (causes patch conflicts):
import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
```

Import statements are fine only in new DeerFolia-specific classes under `cn/lunadeer/mc/deerfolia/`.

## Patch File Format

Patches follow standard git format-patch output:

```
From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001
From: author <email>
Date: ...
Subject: [PATCH] Descriptive title

diff --git a/path/to/File.java b/path/to/File.java
index abc123..def456 100644
--- a/path/to/File.java
+++ b/path/to/File.java
@@ -line,count +line,count @@ context
 context line
-removed line
+added line
 context line
```

Patches are numbered sequentially: `0001-Name.patch`, `0002-Name.patch`, etc.

## Patch Ordering

Patches apply in numeric order. Dependencies matter:
1. `0001-Rebranding.patch` — always first
2. `0002-DeerFolia-configuration.patch` — config system, required by most features
3. Feature patches follow, each potentially depending on earlier ones

## Troubleshooting

### Patch fails to apply
- Run `./gradlew applyAllPatches` to see which patch fails
- The conflict is usually in context lines that changed upstream
- Fix by editing the `.patch` file to update context, or regenerate from a clean state

### Rebuild produces unexpected diff
- Ensure all changes are committed in the subproject before rebuilding
- Unrelated whitespace or formatting changes may sneak in — review the diff carefully

### Build fails after patch modification
- Check that fully qualified names are used (not imports) in upstream patches
- Verify the config system patch (`0002`) is intact if features reference `DeerFoliaConfiguration`
