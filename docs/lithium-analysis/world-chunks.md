# Lithium 世界、调度与区块机制分析（只读，2026-10-15）

本文为 `a81b44b` 的历史分析快照；后续实现及实测见[首批实施报告](../lithium-phase1-results.md)。

本篇是 [迁移价值总评估](../lithium-migration-analysis.md) 的源码核对明细。总体排期以总评估为准。路径以 DeerFolia 仓库根目录为起点；`Lithium/` 指 [固定参考源码](https://github.com/CaffeineMC/lithium/tree/416f68de780612d989d4bd8f1e95c5b2bb31aa97)，不是要求采用的实现版本。

范围：Lithium develop 416f68de780612d989d4bd8f1e95c5b2bb31aa97 的思想，与 DeerFolia a81b44b 26.3 的实际执行代码对比。未做运行性能测试、未修改仓库。下文 P1=值得首批原型和 A/B 验证；P2=有价值但复杂/场景依赖，应第二批；P3=测得热点再做；跳过=已有相应机制或平台不适用。并不按源码文件数量计算覆盖率。

路径缩写：
- D = DeerFolia-server/src/minecraft/java
- L = Lithium/common/src/main/java/net/caffeinemc/mods/lithium

## 最值得新增的机制

### 1. 合并四种高度图更新：缺，P1

思想不是缓存更多状态，而是在移除最高阻挡方块后，四种 heightmap 共用一次向下扫描。Lithium `L/common/world/chunk/heightmap/CombinedHeightmapUpdate.java:13` 先筛选需要更新的 map，`:102` 每一层只取一次 BlockState，再分别判断四种谓词。当前 `D/net/minecraft/world/level/chunk/LevelChunk.java:392` 连续独立更新 MOTION_BLOCKING、MOTION_BLOCKING_NO_LEAVES、OCEAN_FLOOR、WORLD_SURFACE；`D/net/minecraft/world/level/levelgen/Heightmap.java:93` 的各次 update 在顶端被移除时单独往下扫描 (`:107`)。

Moonrise 已优化 heightmap 读写为 char[] 快路 (`Heightmap.java:130`、`:134`)，没有消除这四次扫描。因此应保留 raw 数组和序列化同步，合并搜索部分。收益最明确的是高空平台拆除、采矿机、树木清除、表面大批量编辑；普通地下改方块多数早退，收益有限。每次调用局部状态，天然比全局缓存容易适配 Folia。必须在 owning region 内读取该 chunk，保留各类型谓词、minY/maxY 和原来的更新时点，确保事件/邻接更新看到的高度图已一致。

验证：按同样方块操作序列逐步比较四种 heightmap 全部 256 列；全空气柱、洞穴、叶子/水/含水块、极端高度、自定义维度；再比较保存重载和客户端高度图数据。统计 BlockState 查找次数和实际 CPU，测平台拆除和普通地下更新两种负载，防止一般路径回退。

### 2. 游戏事件 dispatch 不创建空监听器注册表：缺，P1；完整世界级稀疏索引 P2

Lithium `L/mixin/world/game_events/dispatch/GameEventDispatcherMixin.java:60` 通过 chunk/section -> 已有注册表索引获取 listener，不访问空区块；`.../LevelChunkMixin.java:41` 维护加载/卸载，`:84` 更新地图引用。当前 `D/net/minecraft/world/level/gameevent/GameEventDispatcher.java:50` 对通知范围内的各 chunk/section 调 `getListenerRegistry`；而 `D/net/minecraft/world/level/chunk/LevelChunk.java:272` 实际使用 computeIfAbsent，**仅仅投递一个没有接收者的游戏事件也可能创建空 EuclideanGameEventListenerRegistry**。这部分没有被 DeerFolia 的 Bukkit 空事件监听器优化覆盖；这是 Minecraft vibration/game event，不是 Bukkit HandlerList。

建议先吸收最小且有效的思想：为投递路径提供只读“已有注册表”访问器；没有注册表直接跳过，注册 listener 的路径仍按需创建。此方案不用先引入 Lithium 全世界共享索引，不增加跨区域生命周期维护。第二阶段才测区域稀疏索引是否能省下可观 chunk lookup。

收益场景：大量实体移动、脚步/方块事件而多数 section 没有幽匿/悦灵/监守者接收者；可同时减少分配和遍历。复杂幽匿农场也能省空区间，但有接收者的实际事件工作必须照常执行。

Folia/Bukkit 约束：`GameEventDispatcher.java:26` 必须照常触发可取消、可改半径的 GenericGameEvent，不能因没有 NMS listener 提前跳过该 API 事件；`:53` 的 TickThread.isTickThreadFor(...,1) 归属检查必须保留；BY_DISTANCE 排序、重入期间增删监听器和 debug 通知保持原样。若做区域索引，加载/卸载、动态 listener 迁移、region split/merge 都要同步处理，禁止共享普通 HashMap 跨 region 访问。

验证：无接收者高频事件的分配量；Bukkit listener 取消/扩大半径/在事件中放置传感器；传感器、校频传感器、幽匿尖啸体、监守者、悦灵；区块卸载重载和 region split/merge；检查丢/重收、距离排序和声音/红石结果。

### 3. Scheduled ticks 紧凑去重 + 时间/优先级分桶：缺，P2（应拆开实验）

当前 `D/net/minecraft/world/ticks/LevelChunkTicks.java:19` 为 PriorityQueue；`:21` 用 ScheduledTick 对象哈希去重；`:106` hasScheduledTick 创建 probe。Lithium `L/mixin/world/tick_scheduler/LevelChunkTicksMixin.java:33` 为时间+priority 的 AVLTreeMap，每桶是按 subTickOrder 通常追加的数组队列；`:68` 用区块内位置+类型编码为 int 去重。`L/common/world/scheduler/OrderedTickQueue.java:90` 顺序写入直接 append，乱序时延迟排序 (`:134`)。

收益场景：集中液体更新、红石定时器、海量 scheduled block ticks；大量同一时刻同优先级任务时减少堆比较和对象探针。队列很小、每任务时间高度分散时，树节点/每桶数组会增加内存和管理成本，不能泛称全服必赚。

建议先独立验证 primitive 去重/无分配 membership，再评估有小队列回退的分桶方案，不直接替换全部 scheduler。不要照搬 12-bit 类型上限、Y 的 12-bit 掩码或 `(time << 4)` 的范围假设；按当前 registry、维度范围、long 时间验证设计。registry 绑定/重载与类型 ID 生命周期要清楚。

Folia 必须重写适配 `LevelChunkTicks.java:48` offsetTicks（区域时间基准转换）；Moonrise `:38` dirty 判定、`:85/:94/:116` 变更标记、`:132` pack 的 lastSaved 与 **按 subTickOrder 保存** 均不能丢。持久化 pendingTicks、重复 tick 去重、回调 onTickAdded、跨 chunk 调度总顺序须一致。不要把 per-chunk 结构变成全球共享队列。

验证：生成随机 schedule/poll/removeIf/pack/unpack/offset 操作，与当前队列逐步差分；同时间不同 priority、乱序插入、重复位置类型、负延时、区域合并拆分、chunk 卸载保存重载、红石优先级电路；测高聚合与高度分散两类负载的 CPU、分配、每 chunk 内存。先做实验，不应凭理论将其列为第一批低风险上线。

### 4. 流体扩散共享 BFS 与局部数组缓存：部分覆盖，P2

Lithium `L/mixin/block/fluid/flow/FlowingFluidMixin.java:105` 对只有一个可流方向快速处理；`:204` 多方向共享广度搜索，记录可达的初始方向，找到最近落差后结束；`:173` 使用局部小数组缓存 block state。这比各方向独立递归减少重复搜索。

DeerFolia `D/net/minecraft/world/level/material/FlowingFluid.java:371`、`:392` 已移植先做便宜条件的短路；`:437` 仍各方向求 `getSlopeDistance` (`:339`)，但 **原版/上游已有 SpreadContext 的状态与 hole 缓存** (`:577`)。所以“流体没有缓存”不成立；真正剩余是搜索算法重复工作、紧凑缓存和单路快速路径。

收益：大规模流水/熔岩传播、复杂水路、生成/破坏导致的集中流动。半径小、无流动的普通世界未必显著。适配必须保留 Paper 不为流体加载新区块 (`:444` 等)、Bukkit 流动/成源/成块事件、液体混合、各维度流速/搜索半径。当前 canHoldAnyFluid 使用 `WASHED_AWAY_BY_FLUIDS` tag (`:479`)，不要恢复旧版“手列门/告示牌”等规则；局部缓存仅一次纯查询内存活，不能跨插件事件回调或世界改动复用。

验证：穷举小型随机障碍格、单路/多等距落差、门/栅栏/含水/水源再生/岩浆水接触、tag 数据包修改、区块边界/未加载邻区、插件取消/改方块；比较逐 tick 方块状态与事件序列，并测查询数量和堆分配。

### 5. 实体按 chunk/section 次序遍历以改善刷怪计数局部性：部分（区域切分已覆盖；位置分组未见），P3

Lithium `L/mixin/minimal_nonvanilla/spawning/NaturalSpawnerMixin.java:21` 以 entity section 顺序枚举；`.../EntitySectionStorageMixin.java:26` 只走 accessible 且非空 sections。它主要改善 cache locality，**并不是免除全体合格实体的计数**。`.../package-info.java:4` 明确声明浮点累计顺序不同可改变密度限制刷怪的细节。

当前 `D/net/minecraft/world/level/NaturalSpawner.java:83` 已只遍历当前 region 的 loadedEntities；该集合在 `D/io/papermc/paper/threadedregions/RegionizedWorldData.java:347` 是紧凑 ReferenceList，`:588` 直接返回；region split/merge 分配实体维护也已经存在。因此单纯把全世界遍历变为区域遍历的收益已被 Folia 得到，Lithium 重排要付出从紧凑数组改成分层迭代或维护空间顺序的成本。

此外 26.3 `NaturalSpawner.java:100` 从 **环境属性按实体位置** 取 mob spawn cost；不能因同 chunk/section 就直接缓存同一个 biome/cost。需要保留 natural spawn reason 配置 (`:92`)、per-player counts (`:112`)、自定义 persistence、跨区实体迁移。建议仅在 profiler 证明 createState 是实际热点时做 P3 局部性实验，且单独选择是否接受浮点顺序差异；默认不改变刷怪语义。

验证：不同密度下 compact list vs section traversal、每 region 而非全局计时；Nether density-controlled spawning、自然/插件/生成实体、命名怪/驯服动物、per-player cap、多玩家跨区、传送迁移、环境属性数据包；验证 counts 精确一致，再显式检查 spawnPotential 浮点/结果差异。

### 6. Block pattern 必需材料预筛：缺，P3（尖峰优化）

Lithium `L/mixin/block_pattern_matching/BlockPatternMixin.java:29` 先检查必要方块数量，`.../EnderDragonFightMixin.java:35` 为出口门设41块 bedrock；`L/common/world/block_pattern_matching/BlockSearch.java:17` 先 palette maybeHas，再顺序扫描。当前 `D/net/minecraft/world/level/block/state/pattern/BlockPattern.java:76` 直接位置×方向匹配；EnderDragonFight.java:354 对多高度重复 find。

价值是末地出口门搜索、损坏/非标准出口结构的单次尖峰，正常 TPS 平均收益有限。建议从已知 pattern 动态推导安全必要条件，或仅静态且验证过的末地 pattern，不把41硬编码当通用优化。预筛只允许假阳性，不能假阴性；保留未知谓词的原始路径；不要为预筛额外加载原先无需加载的 chunks，也不越区访问。验证门存在/缺失/损坏、末影龙复活、插件修改末地结构、垂直和 chunk 边界，比较匹配位置和加载集合。

## 已有对应优化，默认不再迁入

| 机制 | 当前证据 | 状态/决定 |
| --- | --- | --- |
| 稀疏随机 tick 选择 | `D/net/minecraft/server/level/ServerLevel.java:1065` 使用 section tickingBlocks 索引、4096概率抽样；`LevelChunkSection.java:31` 维护列表 | 已由 Moonrise/Paper 覆盖思想，且 Folia 用线程本地 random；跳过重复随机 tick 重写 |
| 去掉温度缓存 | `D/net/minecraft/world/level/biome/Biome.java:124` 直接 getHeightAdjustedTemperature | 已由 Paper 覆盖；不要根据仍存在的未使用 temperatureCache 字段误判 |
| 晴天无水时跳过降雪/结冰工作 | `D/net/minecraft/server/level/ServerLevel.java:1178` | DeerFolia 已移植；跳过 |
| 结冰判断复用已取 blockstate 的 fluidstate | `D/net/minecraft/world/level/biome/Biome.java:139` | DeerFolia 已移植；跳过 |
| 流体状态展平/属性缓存 | `D/net/minecraft/world/level/material/FluidState.java:28` isEmpty、`:39` amount/source/height/randomTick | Paper/Moonrise 已覆盖更多同类属性；跳过 |
| 世界/区块高度内联 | `D/net/minecraft/world/level/Level.java:733`、`ChunkAccess.java:483` | 已缓存范围/section 并直接返回；跳过 |
| 方块/流体读取短路径、利于 JVM 内联 | `D/net/minecraft/world/level/chunk/LevelChunk.java:285`、`:352`，`Level.java:987` | 已由 Paper 实现；跳过 |
| loaded chunk 快速访问 | `D/net/minecraft/server/level/ServerChunkCache.java:314` 用 fullChunks 直接读，`:287` 即时查询；`:276` 明示 MT-safe 路径绕过旧 cache | Moonrise 重写已替代 Lithium 针对 vanilla four-entry cache/future/ticket 的优化；勿把共享4项cache重新搬入 Folia |
| 仅扫描可过期 tickets | `D/ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkHolderManager.java:950` 按 region owned sections，`:971` chunkToExpireCount，`:989` 过期 | Moonrise/Folia 已覆盖且有 region/area lock；跳过 |
| Palette 快速哈希表 | `D/net/minecraft/world/level/chunk/Strategy.java:9` FastHashMapPalette | 主体已覆盖，跳过替换；但小 palette 的阈值不同（`:39`、`:58` 仍 linear），阈值改动仅 P3 基准实验，不能称完全等价 |
| Palette 压缩/序列化 | `D/net/minecraft/world/level/chunk/PalettedContainer.java:325` thread-local readout，`:366` 单值，`:374` 不变则copy，`:408` 重打包 | Moonrise 已覆盖主要思想，别覆盖 Anti-Xray/preset 和复制一致性 |
| 位存储 hot path 取消验证/更便宜操作 | `D/net/minecraft/util/SimpleBitStorage.java:293`、`:344`；`ZeroBitStorage.java:16` | 已由 Paper 实现；跳过 |
| 移除 palette debug lock | `D/net/minecraft/world/level/chunk/PalettedContainer.java:30` threadingDetector 注释，acquire/release无操作 | 调试检查已移除；**实际 synchronized** 的 resize/set/read/write/pack保留，这是并发一致性用途，不能按 Lithium no_locking 删除 |
| 生成器海平面缓存 | `D/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java:504` | DeerFolia 已移植（lazy holder bound），跳过 |
| NeoForge chunk_load_tricks | Lithium 仅 neoforge `.../chunk_load_tricks/ChunkLoadTricksMixin.java` | 平台兼容垫片，不是 DeerFolia 待补优化；Moonrise getChunkNow `ServerChunkCache.java:334` 本已有平台挂钩，跳过 |

## 排期建议

本范围首批：合并 heightmap 更新、投递时不创建空 listener registry。两者有明确执行证据，边界可局部控制，收益目标可直接量化。第二批：scheduled tick 先紧凑去重再分桶、流体共享 BFS；大型投入需独立基准与差分验证。完整区域 game-event 稀疏索引等轻量方案验证后再决定。按 section 重排刷怪计数、pattern 预筛、小 palette 阈值作为 P3，先看目标服务器热点。

所有候选对比的基线必须是当前 DeerFolia（包含现有优化），不是 vanilla；应报告 region tick 时间分位数、目标方法 CPU/分配、每 chunk 内存及原有机制验证，不捏造全服收益百分比。此报告没有声称现有未迁入模块全部有益，也没有用 Minecraft 版本差异替代思想评估。

补充细分：Lithium `chunk.serialization` 也将 PalettedContainer.count 的哈希计数改为 palette 大小数组 (`L/mixin/chunk/serialization/PalettedContainerMixin.java:126`)。DeerFolia 通用 `PalettedContainer.java:478` 确实仍是 Int2IntOpenHashMap；但主要 section recount 路径已经在 `LevelChunkSection.java:192` 完全绕过它，`:213` 使用 Moonrise `moonrise$countBlocks`。故应标“通用方法尚未改、主要调用路径已替代”，P3 / 待 profiler 证明剩余活跃调用，而不能据方法差异声称区块计数缺失。
