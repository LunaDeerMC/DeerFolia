---
name: deerfolia-upstream-update
description: Update DeerFolia's upstream Folia reference and resolve patch conflicts. Use when upgrading Minecraft version, syncing with latest Folia commits, resolving patch application failures after upstream changes, or when user mentions upstream update, Folia update, version bump, foliaRef, or patch conflicts. Covers the full upstream synchronization workflow.
---

# Updating DeerFolia Upstream

## Overview

DeerFolia tracks Folia via a git commit ref in `gradle.properties`. Updating upstream means pointing to a newer Folia commit and resolving any patch conflicts.

## Key File

`gradle.properties` contains:
```properties
foliaRef = <commit-hash>    # Folia commit to build against
mcVersion = 1.21.11         # Minecraft version
version = 1.21.11-R0.1-SNAPSHOT
```

## Update Workflow

### 1. Identify Target Commit

Find the desired Folia commit from https://github.com/PaperMC/Folia.

For a Minecraft version bump, find the Folia commit that targets the new version.

### 2. Update foliaRef

Edit `gradle.properties`:
```properties
foliaRef = <new-commit-hash>
```

If the Minecraft version changed, also update `mcVersion`, `version`, and `apiVersion`.

### 3. Apply Patches

```bash
./gradlew applyAllPatches
```

This will:
1. Clone/update the Folia source at the new ref
2. Apply DeerFolia patches on top

**If patches apply cleanly** → proceed to build verification (step 5).

**If patches fail** → resolve conflicts (step 4).

### 4. Resolve Patch Conflicts

When a patch fails to apply, the error message indicates which patch and which file has conflicts.

**Strategy A: Fix the patch file directly**
1. Open the failing `.patch` file
2. Update context lines to match new upstream code
3. Adjust line numbers if needed
4. Re-run `./gradlew applyAllPatches`

**Strategy B: Incremental rebase (recommended for complex conflicts)**
1. Apply patches up to the one before the failing patch
2. Manually apply the changes described in the failing patch
3. Mark all changes with `// DeerFolia` comments
4. Commit the changes
5. Continue with remaining patches
6. Rebuild all patches: `./gradlew rebuildAllServerPatches`

**Common conflict causes:**
- Upstream renamed/moved methods or fields
- Upstream changed code near DeerFolia modifications
- Upstream added new code between DeerFolia's context lines
- Mojang mapping changes (class/method names changed)

### 5. Build Verification

```bash
./gradlew createMojmapPaperclipJar
```

Fix any compilation errors — typically caused by:
- API changes in upstream (method signatures changed)
- Removed/renamed classes or methods
- New required method overrides

### 6. Rebuild Patches

After all conflicts are resolved and the build succeeds:

```bash
./gradlew rebuildAllServerPatches
```

Verify the updated patches look correct.

### 7. Commit

Commit all changes to the DeerFolia repository:
- Updated `gradle.properties`
- Updated/new patch files
- Any new DeerFolia source files if needed

## Patch Dependency Order

Remember patches apply sequentially. When fixing conflicts:
1. `0001-Rebranding.patch` — branding changes
2. `0002-DeerFolia-configuration.patch` — config system (most features depend on this)
3. `0003` through `0011` — individual features

A conflict in `0002` can cascade to all later patches.

## Build System Details

- Build tool: paperweight patcher v2.0.0-beta.19
- Java: 21 (Gradle toolchain)
- Upstream: `PaperMC/Folia` on GitHub
- The `build.gradle.kts` defines patch directories and upstream mapping

## CI/CD

GitHub Actions (`.github/workflows/build.yml`) runs on tag push:
1. Checkout → Setup JDK 21 → Apply patches → Build → Release JAR

Ensure patches apply and build succeeds before tagging a release.
