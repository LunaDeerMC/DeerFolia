# Repository Guidelines

## Project Structure & Module Organization

DeerFolia is a Gradle/paperweight patch fork of Folia. The root project includes `DeerFolia-api` and `DeerFolia-server`; generated upstream worktrees such as `paper-api`, `paper-server`, `folia-api`, and `folia-server` are build artifacts. Server-specific Java additions live under `DeerFolia-server/src/main/java`, especially `cn/lunadeer/mc/deerfolia`. Upstream modifications are stored as patches in `DeerFolia-server/minecraft-patches`, `DeerFolia-server/paper-patches`, and `DeerFolia-server/folia-patches`. Documentation belongs in `docs/`; non-code reference assets belong in `resources/`.

## Build, Test, and Development Commands

- `./gradlew applyAllPatches`: applies upstream Folia/Paper plus DeerFolia patches and refreshes source worktrees.
- `./gradlew createPaperclipJar`: builds the runnable server jar in `DeerFolia-server/build/libs`.
- `./gradlew rebuildAllServerPatches`: regenerates server patch files after committing source changes in the patched worktree.
- `./gradlew rebuildPatches`: rebuilds patch files after editing existing patches.
- `./gradlew updateFoliaRef`: updates the tracked upstream Folia reference from `gradle.properties`.
- `./gradlew test`: runs available Gradle/JUnit tests.

Use a Java 25-capable JDK or working Gradle toolchain setup; source compilation is configured with `options.release = 25`.

## Coding Style & Naming Conventions

Follow surrounding Java style and standard Oracle-style formatting. Use spaces, keep lines readable, and prefer existing project patterns over new abstractions. Mark changes inside upstream-owned files with DeerFolia comments: `// DeerFolia start - <reason>`, `// DeerFolia end - <reason>`, or `// DeerFolia - <reason>` for one-line changes. To reduce patch conflicts, prefer fully qualified class names over new imports when touching upstream files. New DeerFolia classes should use package names under `cn.lunadeer.mc.deerfolia`.

## Testing Guidelines

Add focused tests when behavior can be validated with existing JUnit infrastructure. Name test classes with the `*Test` suffix under the relevant module’s `src/test/java` tree. For networking, async pathfinding, and region-thread behavior, also document manual verification steps in the PR because some Folia behavior is integration-heavy.

## Commit & Pull Request Guidelines

Recent history uses short imperative Conventional Commit prefixes such as `feat:` and `fix:`; keep subjects concise and scoped, for example `feat: add AFK packet resync stats`. Commit source changes in the relevant patched module before rebuilding patches. Pull requests should describe the feature or bug, list touched patch files, include build/test results, mention config changes, and link related issues. Include screenshots or console output only when they clarify user-visible behavior.

## Configuration & Patch Notes

New configuration options belong in `DeerFolia-server/src/main/java/cn/lunadeer/mc/deerfolia/DeerFoliaConfiguration.java` with `@Comments` documentation where appropriate. Keep new standalone DeerFolia code separate from upstream patches when possible; this keeps patch files smaller and easier to rebase.
