# Lithium 第一批优化的本地端到端测试

这些源码构建一个独立的 Folia 测试插件，配合真实 TCP 客户端、隔离测试世界和
A/B runner 使用。源码位于本目录；所有生成文件留在 `build/lithium-phase1/`。
当前适用 **Minecraft 26.3（协议版本 777）**，客户端从应用补丁后的协议源码读取
数据包 ID。升级 Minecraft 后需要重新核对客户端数据包格式。

需要 Python 3.9+、Java 25 JDK（含 `java`、`javac`、`jar`）和 `curl`。
脚本优先使用 `JAVA_HOME/bin`，未设置时使用 PATH；不依赖特定云环境路径。
从仓库根目录准备源码、服务端与运行时 classpath：

```sh
./gradlew applyAllPatches
./gradlew createPaperclipJar
./gradlew -I tools/lithium-phase1-e2e/classpath.init.gradle :DeerFolia-server:e2eClasspath
python3 tools/lithium-phase1-e2e/compile.py
python3 tools/lithium-phase1-e2e/run.py --modes on --no-benchmark --tag smoke
python3 tools/lithium-phase1-e2e/run.py --modes off,on,on,off --restart --tag abba
```

命令可在任意工作目录调用；仓库位置通过脚本自身位置确定。上面的 Gradle 命令
仍应在仓库根目录运行。每次测试使用不同的 `--tag`，避免复用之前的测试世界。
编译器也接受 `--classpath-file PATH`，用于显式指定已有 classpath 导出文件。
runner 还会校验并复制上述 Gradle 构建生成的原版服务端缓存
`DeerFolia-server/.gradle/caches/paperweight/taskCache/downloadServerJar.jar`。
清理该缓存后需重新运行构建；只有预构建 Paperclip JAR 不足以运行此工具。

运行时若需要代理或自定义证书，脚本会读取 `${GRADLE_USER_HOME}/gradle.properties`
（未设置时为 `~/.gradle/gradle.properties`）中的 `systemProp.*` JVM 参数；文件不存在
时直接运行。可用 `LITHIUM_E2E_GRADLE_PROPERTIES=/path/to/gradle.properties` 指定其他
文件。官方服务转发器通过 `curl` 访问官方 HTTPS 地址，并遵循 curl 的代理环境变量；
它只将 discovery 中的官方 URL 转为本机回环地址，不伪造公钥或禁用验证。

测试服只监听 `127.0.0.1`，使用本地 offline-mode 账号，并在隔离测试目录写入
`eula=true`。此工具不验证正版账号登录。产物、测试服世界、插件、日志和 JSON
结果均位于 `build/lithium-phase1/`；不会写入生产服务端配置或世界。

另可指定先前版本的 JAR 做参考测量（该文件需要自行准备）：

```sh
python3 tools/lithium-phase1-e2e/run.py --baseline --jar build/lithium-phase1/baseline-a81b44b.jar --modes off --tag original-reference
```

旧产物参考仅支持 `--modes off`，不支持 `--restart`；重启功能断言需要最终产物的新接口。

Only localhost ports 25577 (Minecraft) and 25576 (official-services relay) are used.
Test worlds and logs live below `build/lithium-phase1/runs` and `results`. The runner
stops its server and relay on completion/failure. Existing relay processes are reused
and left alone. Each measurement process gets a fresh flat world and the same seed.

Both arms use the **same final JAR with the safety fixes**. Existing Lithium flags
and the master switch remain enabled. The seven new phase-one flags are the only
configuration differences. The old a81b44b artifact is retained for reference and
is not used to attribute speedups from unrelated safety changes. A separate
`--baseline` reference run measures the identical benchmark scenarios on that
original artifact, skipping functional checks for the new APIs. This additional
comparison can expose common new costs, such as Brain memory version maintenance,
which the fixed-safety OFF/ON comparison cannot measure. Do not merge its samples
into the OFF/ON groups. Each invocation writes a distinct `{tag}-summary.json`.

Two real protocol clients log in, receive chunks and acknowledge teleportation.
The players are placed 2048 blocks apart on two Folia region threads. World accesses,
integration assertions and benchmarks run on their entity/region schedulers. Both
regions stay active; timed benchmark phases run in each region sequentially.

Functional coverage includes collector order and lazy allocation, randomized full
heightmap scan oracles for four map types, game-event delivery modes/cancellation/
expanded radius/reentrancy/unregistering, real Brain ticks and memory/TTL/topology
invalidation, egg search across sections with addition/removal/obstruction, sleeping
and waking four block entity classes, live Bukkit state mutation and campfire item
mirrors, hopper redstone transfer, async snapshot isolation/unsafe collision rejection/
owner-thread completion/mob movement, POI distance separation in two worlds at identical
coordinates, saved block entities and waking after restarting the same world.

There are 29 timed scenarios. Each defaults to twenty warmup rounds and twenty measured rounds per region.
Use `--warmup-rounds` and `--sample-rounds` to explicitly select other counts;
the counts are recorded in the raw result. Earlier diagnostic runs used four/ten. The
samples report current-thread allocated bytes and CPU time through ThreadMXBean,
wall-clock nanoseconds, operation count and observable work count. Active scenarios accompany
empty/idle cases. The summary compares medians and verifies equal work counts across
arms; raw samples remain available to assess outliers and process-order effects.

Block entity measurements use actual LevelChunk `TickingBlockEntity` wrappers,
including wrapper validation/profiling and any checks in that wrapper. There are
both individual wrapper samples and batches of 64 real block entities of each class
in idle and active states. These measurements **exclude the outer Level scheduler**;
they do not claim to measure complete natural region ticks or whole-server throughput.
Repeated synthetic ticks share the current server game time. Active output/progress
is checked separately through normal ticking in the integration phase.

Local offline-mode test accounts are used. This does not cover authenticated login,
arbitrary third-party plugins, all redstone contraptions or all gameplay scenarios.
Subsystem timings should not be presented as universal TPS improvements. Brain
scenarios call complete Brain.tick on real villagers with synthetic 96-task and
4-task activity graphs, including stable unmet conditions, active conditions and
per-tick memory presence churn. They retain the villager sensors, but they are not
benchmarks of an untouched vanilla villager task graph.

Async collision safety intentionally rejects chunks whose palettes may contain
dynamic shapes. Palette entries can outlive removal of the corresponding block,
so a removed powder-snow/shulker/piston state may continue to cause conservative
synchronous fallback until the palette is rebuilt. Positive asynchronous coverage
uses a fresh static corridor before injecting an unsafe state.
