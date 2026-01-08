Contributing to DeerFolia
==========================

## Use a Personal Fork and not an Organization

Paper will routinely modify your PR, whether it's a quick rebase or to take care
of any minor nitpicks we might have. Often, it's better for us to solve these
problems for you than make you go back and forth trying to fix them yourself.

Unfortunately, if you use an organization for your PR, it prevents Paper from
modifying it. This requires us to manually merge your PR, resulting in us
closing the PR instead of marking it as merged.

We much prefer to have PRs show as merged, so please do not use repositories
on organizations for PRs.

See <https://github.com/isaacs/github/issues/1681> for more information on the
issue.

## Requirements

To get started with PRing changes, you'll need the following software, most of
which can be obtained in (most) package managers such as `apt` (Debian / Ubuntu;
you will most likely use this for WSL), `homebrew` (macOS / Linux), and more:

- `git` (package `git` everywhere);
- A Java 17 or later JDK (packages vary, use Google/DuckDuckGo/etc.).
    - [Adoptium](https://adoptium.net/) has builds for most operating systems.
    - Paper requires JDK 17 to build, however, makes use of Gradle's
      [Toolchains](https://docs.gradle.org/current/userguide/toolchains.html)
      feature to allow building with only JRE 11 or later installed. (Gradle will
      automatically provision JDK 17 for compilation if it cannot find an existing
      install).

If you're on Windows, check
[the section on WSL](#patching-and-building-is-really-slow-what-can-i-do).

If you're compiling with Docker, you can use Adoptium's
[`eclipse-temurin`](https://hub.docker.com/_/eclipse-temurin/) images like so:

```console
# docker run -it -v "$(pwd)":/data --rm eclipse-temurin:17.0.1_12-jdk bash
Pulling image...

root@abcdefg1234:/# javac -version
javac 17.0.1
```

## Formatting

All modifications to non-Paper files should be marked. The one exception to this is
when modifying javadoc comments, which should not have these markers.

- You need to add a comment with a short and identifiable description of the patch:
  `// DeerFolia start - <COMMIT DESCRIPTION>`
    - The comments should generally be about the reason the change was made, what
      it was before, or what the change is.
    - After the general commit description, you can add additional information either
      after a `;` or in the next line.
- Multi-line changes start with `// DeerFolia start - <COMMIT DESCRIPTION>` and end
  with `// DeerFolia end - <COMMIT DESCRIPTION>`.
- One-line changes should have `// DeerFolia - <COMMIT DESCRIPTION>` at the end of the line.

Here's an example of how to mark changes by DeerFolia:

```java
entity.getWorld().dontBeStupid(); // DeerFolia - Was beStupid(), which is bad
entity.getFriends().forEach(Entity::explode);
entity.updateFriends();

// DeerFolia start - Use plugin-set spawn
// entity.getWorld().explode(entity.getWorld().getSpawn());
Location spawnLocation = ((CraftWorld)entity.getWorld()).getSpawnLocation();
entity.getWorld().explode(new BlockPosition(spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ()));
// DeerFolia end - Use plugin-set spawn
```

We generally follow the usual Java style (aka. Oracle style), or what is programmed
into most IDEs and formatters by default. There are a few notes, however:
- It is fine to go over 80 lines as long as it doesn't hurt readability.  
  There are exceptions, especially in Spigot-related files
- When in doubt or the code around your change is in a clearly different style,
  use the same style as the surrounding code.

### Imports
When adding new imports to a class in a file not created by the current patch, use the fully qualified class name
instead of adding a new import to the top of the file. If you are using a type a significant number of times, you
can add an import with a comment. However, if its only used a couple of times, the FQN is preferred to prevent future
patch conflicts in the import section of the file.

```java
import org.bukkit.event.Event;
// don't add import here, use FQN like below

public class SomeEvent extends Event {
    public final org.bukkit.Location newLocation; // Paper - add location
}
```

## New Configuration Options

When adding new configuration options, please make sure to add them to the `DeerFolia-server\src\main\java\cn\lunadeer\mc\deerfolia\DeerFoliaConfiguration.java`, if you want 
to add documentation for the configuration option, please use Annotation in the field, like this:

```java
    @Comments("Configuration option example")
    public static boolean someOption = true;

    public static class SubConfigExample extends ConfigurationPart {
        @Comments("Sub-configuration option example")
        public int anotherOption = 42;
    }

    @Comments("Sub-configuration example")
    public static SubConfigExample subConfigExample = new SubConfigExample();
```

### Accessing Configuration Options

You can access the configuration options directly through the `cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration` class, like this:

```java
boolean opt = cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration.someOption;
int val = cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration.subConfigExample.anotherOption;
```

## New Class or Package File

If you want add a whole new class file or package, add them to `DeerFolia-server\src\main\java\cn\lunadeer\mc\deerfolia`.
