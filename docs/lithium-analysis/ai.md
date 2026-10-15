# Lithium AI／寻路／POI 思想移植评估（静态源码核对）

本文为 `a81b44b` 的历史分析快照；后续实现及实测见[首批实施报告](../lithium-phase1-results.md)。

本篇是 [迁移价值总评估](../lithium-migration-analysis.md) 的源码核对明细。总体排期以总评估为准。路径以 DeerFolia 仓库根目录为起点；`Lithium/` 指 [固定参考源码](https://github.com/CaffeineMC/lithium/tree/416f68de780612d989d4bd8f1e95c5b2bb31aa97)，不是要求采用的实现版本。

核对日期 2026-10-15；基线 `DeerFolia` 的 26.3.x / a81b44b。参考 Lithium `Lithium` develop / 416f68de780612d989d4bd8f1e95c5b2bb31aa97。版本用于固定证据，不是迁移边界。本次只读分析，没有实现、运行基准或构建，收益均为待验证的场景假设。

下面路径缩写：

- `N` = `DeerFolia-server/src/minecraft/java`
- `D` = `DeerFolia-server/src/main/java`
- `B` = `paper-server/src/main/java`
- `L` = `Lithium/common/src/main/java/net/caffeinemc/mods/lithium`

优先级是投入次序，不表示已测得收益：P1 值得先做原型和差分测试；P2 条件收益明显但适配面较大或场景较窄；P3 先由实际 profile 证明；跳过＝已有核心机制或不适用，不代表所有代码等价。

## 结论

AI 最值得先做的是 Brain 可运行／正在运行任务索引，以及记忆状态变更驱动的启动条件缓存。它们目前没有覆盖，作用于大量使用 Brain 的生物；DAB 只是减少 Brain 执行频率，不能消除每次真正执行时的重复遍历。海龟蛋搜索可作为独立、容易测量的 P1 场景，但猪灵驱避物不能也列为完整缺失：26.3 原生新增的 FilteredSectionCache 已有 palette 排除。

寻路应重点评估方块状态级路径类型缓存、区域 section 危险标记、PathNavigationRegion 读取路径，以及活动导航监听集合。已有坐标缓存和异步路径并不等价覆盖这些，但新缓存必须先解决异步输入、所有权、失效和地区迁移。

POI 主查询、最近 N 个、随机候选、传送门搜索已由 Paper/Moonrise 大幅改写。整体替换收益不明且冲突面大，建议保留其架构，只调查缺失的直接类型索引／精确坐标占用接口。现有 DeerFolia “POI 优化”另有距离缓存和降频策略，不应与等价结构优化混称。

## 分项核对

### A1 Brain 可运行任务列表与运行状态索引 — 缺失，P1

思想：活动集合或行为配置变化时才重建有资格尝试的任务列表；启动／停止时维护运行集合。避免每次 tick 遍历 priority→activity→set，以及重新分配 running list。

- Lithium：`L/mixin/ai/task/launch/BrainMixin.java:41` 保存 possibleTasks/runningTasks；`:53` 初始化；`:102` 启动；`:118` 直接返回运行集合；`:131`、`:147`、`:160`、`:174`、`:189` 失效和状态维护。
- 当前：`N/net/minecraft/world/entity/ai/Brain.java:268` 每次 new ObjectArrayList 后扫描所有任务；`:418` 每次重新扫描优先级／活动；`:438` 每 tick 调用 getRunningBehaviors。现有 GateBehavior 局部循环优化没有覆盖 Brain 总调度。
- 收益场景：密集村民、猪灵、动物群，尤其玩家附近 DAB 不明显降频的区域；空闲行为很多而真正运行的行为少时更可能有收益。
- 设计：每个 Brain 自己拥有索引和版本；保留优先级、活动和行为迭代顺序，组内遵循原 Set 去重、跨组重复注册保留原来的重复 tick；addActivity/removeAllBehaviors/setActiveActivity、stopAll、嵌套 Gate、重建 Brain 都进入失效图；列表迭代期间行为改变需固定批次或延迟维护。
- Folia：跟随实体所属区域，只在实体调度线程变更；保留 `Brain.java:403` stopAll 对传送过程不读取当前 world data 的特殊处理，不能照搬 Lithium 的整体覆盖方法。
- 验证：同种子追踪启动／tick／stop 的顺序与数量；白天工作→睡眠→恐慌→战斗；刷新 Brain、传送、region split/merge；统计每 tick 分配和完整遍历次数。

### A2 记忆存在性版本与任务前置条件缓存 — 缺失，P1

思想：启动所要求的 PRESENT／ABSENT／REGISTERED 状态未变，复用 hasRequiredMemories 结果。不是缓存实体目标对象，不应把值内变化和存在性变化混淆。

- Lithium：`L/mixin/ai/task/memory_changes/BehaviorMixin.java:26` 版本和结果缓存，`:41` 检查；`BrainMixin.java:27` 注册／清理，`:43` 过期删除，`:57`、`:74`、`:89` 写入失效。
- 当前：`N/net/minecraft/world/entity/ai/behavior/Behavior.java:112` 每次遍历 entryCondition；`Brain.java:154`、`:158`、`:186`、`:201` 集中写入口；`:398` 过期 ticking；`memory/MemorySlot.java:16` 过期真正清除时间点。`behavior/declarative/MemoryAccessor.java:24` 等写操作仍走 Brain API，利于集中维护。
- 收益场景：大量任务在稳定缺失记忆下反复尝试启动；结果负缓存尤其有价值。
- 设计：先全 Brain 状态版本，包含注册、清空、写入空/非空切换、过期。只在状态变化增版本，值非空→另一个非空无需为“存在性检查”失效。缓存键必须包含 Brain 实例／epoch，不能只比较跨实体相同数字；不要缓存 checkExtraStartConditions（依赖世界／时间）。若测得全局版本经常失效，再考虑按记忆依赖版本，不要一开始做复杂依赖图。
- 边界：此方案只直接覆盖 Behavior 子类；OneShot/BehaviorBuilder 的声明式前置条件仍需要单独分析，不能承诺全部 AI 前置判断都消失。可变 entryCondition 或动态自定义行为需冻结结构或显式失效。
- DAB：维持当前 Brain tick 的过期语义，不能顺带改成世界时间计时。Bukkit setMemory、异步寻路完成写记忆必须在实体 owner 线程发生且触发失效。
- 验证：PRESENT/ABSENT/REGISTERED 全组合；清空、过期、同 tick 传感器写入后启动、异步 POI 回调、重建 Brain；对比任务轨迹并测缓存命中率。

### A3 Brain 集合使用 identity／紧凑集合 — 缺失，P2，附着 A1/A2

- Lithium：`L/mixin/collections/brain/BrainMixin.java:46` 将 memories/sensors/activityRequirements 替换为 fastutil，传感器使用 linked map 保序。
- 当前：`N/net/minecraft/world/entity/ai/Brain.java:40` HashMap、`:41` LinkedHashMap、`:44` HashMap。
- 价值：减少节点对象和键查找成本；宜作为 A1/A2 的局部配套，不要宣传成独立大幅提速。
- 约束：只对有 identity 契约的注册单例键使用 reference map；保留 sensor 顺序，不替换带相等性语义的 key；紧凑集合仍不是线程安全集合。
- 验证：堆占用/分配、查询 CPU、传感器顺序一致性；不同实体规模下评估额外容量成本。

### A4 GateBehavior／ShufflingList／RunningPolicy 去 Stream — 已覆盖核心，跳过重复移植

- Lithium：`L/mixin/ai/task/replace_streams/`。
- 当前：`N/net/minecraft/world/entity/ai/behavior/GateBehavior.java:72` RunningPolicy 直接接收 iterable；`:80` Paper 循环及 DeerFolia 同次计算是否仍运行；`:103` 停止循环；`:140` RUN_ONE/TRY_ALL 无 Stream。调试 debugString 中 Stream 不是 tick 热点，无需为数量补齐。

### A5 诱惑玩家传感器单遍选最近 — 已移植，跳过

- Lithium：`L/mixin/ai/sensor/replace_streams/tempting/TemptingSensorMixin.java:33`。
- 当前：`N/net/minecraft/world/entity/ai/sensing/TemptingSensor.java:41` 已有 DeerFolia 单次循环，还必须保留 Bukkit 目标事件路径，不能用 Lithium doTick 全覆盖覆盖掉服务端逻辑。

### A6 无副工作点职业提前退出 — 已移植，跳过

- Lithium：`L/mixin/ai/sensor/secondary_poi/SecondaryPoiSensorMixin.java:20`。
- 当前：`N/net/minecraft/world/entity/ai/sensing/SecondaryPoiSensor.java:29` 同样检查 profession.secondaryPoi().isEmpty()、清空记忆、返回。DeerFolia 的可调间隔是另外一项行为策略，不是这一等价优化的必要部分。

### A7 不可用行为／传感器停用（傻子找工作、成年生物找父母、山羊找物品） — 缺失，P2

- Lithium：`L/mixin/ai/useless_behaviors/nitwit_job_search/VillagerGoalPackagesMixin.java:38`；`L/mixin/ai/useless_sensors/baby_specific_sensors/AgeableMobMixin.java:51`；`L/mixin/ai/useless_sensors/goat_item_sensor/GoatMixin.java:30`；`L/common/ai/brain/SensorHelper.java:13` 用极大 countdown 保留 sensor 实例，恢复时取回随机相位。
- 当前：`N/.../ai/behavior/VillagerGoalPackages.java:51` 无条件建立职业 AcquirePoi；`N/.../animal/goat/Goat.java:72` 仍注册 NEAREST_ITEMS/NEAREST_ADULT；`N/.../npc/villager/Villager.java:135` 仍注册 VILLAGER_BABIES。
- 收益场景：大量成年 Brain 动物、无用职业村民、掉落物密集山羊区；收益依赖停掉的传感器到底有多重和 DAB 配置。
- 适配：先证明记忆无原生消费者；Bukkit/NMS 插件可读写记忆或替换行为，最好明确支持范围与 opt-out。使用明确 enabled/disabled 状态优于超大倒计时技巧；保留随机错峰和恢复周期。
- 关键差异：`B/org/bukkit/craftbukkit/entity/CraftAgeable.java:25,40` 支持 setAge/setBaby；不能沿用 Lithium 包说明“无法不重建 Brain 变回幼体”的假设。`CraftVillager.java:56` setProfession 只调用 setVillagerData；当前 `N/.../Villager.java:578` 并不直接 refreshBrain。必须跟随年龄／职业变化恢复正确行为，不能永久删除后依赖不存在的刷新保证；`Villager.java:205` refreshBrain、`:223` ageBoundaryReached 也需重新设置停用状态。
- 可先做更保守的每次 eligible 入口短路，再评估移除调度列表，避免把缓存变为新的可见行为差异。
- 验证：Bukkit 成人↔幼体、傻子↔普通职业、加载保存、重建脑、插件设记忆；无用行为调用为零，但有效行为在状态变化后恢复。

### A8 跳跃候选加权抽样 — 缺失，P2（大型青蛙／山羊场景）

- Lithium：`L/mixin/ai/task/run/long_jump_weighted_choice/LongJumpToRandomPosMixin.java:47,58`；`L/common/util/collections/LongJumpChoiceList.java:13` 说明权重总和与移除成本，`:32` 只读模板，`:138` 每次复制可变候选，`:158` 抽样并 swap-remove。
- 当前：`N/.../ai/behavior/LongJumpToRandomPos.java:132` 生成所有坐标+PossibleJump；`:187` 每次 WeightedRandom 及 List.remove。
- 设计：共享不可变相对偏移模板、每行为保留独立候选／总权重，延迟生成绝对坐标对象；必要时按距离平方分桶。保留概率分布，但抽样随机数消耗或排序通常会变化，不能声称同种子选择逐次一致。
- Folia：模板必须不可变安全发布；每只生物自己的可变候选不能跨 region 共享。保持当前异步 path.isProcessed 交互；跳跃候选判定里 createPath/canReach 不可因为新结构顺便改变等待语义。
- 验证：权重分布统计、每候选最多一次、空集合和自定义大范围回退、同地形可达性与着陆、实际候选对象分配；先确认 profiler 中 long jump 候选是热点。

### A9 袭击 bossbar 更新合并 — 已移植，跳过

- Lithium：`L/mixin/ai/raid/RaidMixin.java:29,43` dirty flag。
- 当前：`N/.../entity/raid/Raid.java:644` 已有 lithiumBossbarDirty 合并。不能把整个 ai.raid 包都视为已移植，旗帜模板是独立缺项。

### A10 袭击队长旗帜比较模板缓存 — 缺失，P2（局部小项目）

- Lithium：`L/mixin/ai/raid/RaiderMixin.java:30,51` 和 `Raider$ObtainRaidLeaderBannerGoalMixin.java:29` 读取 world-scoped 旗帜模板。
- 当前：`N/.../entity/raid/Raider.java:50,153,216,413` 每次构造 ominous banner；`Raid.java:714,718` 每次 template/create。
- 设计：利用当前 ItemStackTemplate 概念，缓存不可变比较用组件／模板，按注册表生命周期失效；发放装备仍 create 新 ItemStack，不能把可变 ItemStack 直接共享给实体或 Bukkit。
- 收益：袭击塔、大量掠夺者和候选掉落物下减少重复组件构造；对普通服务器可能很小。
- 验证：旗帜所有组件/名称匹配、旗帜 NBT 改动、装备后损坏／插件修改不污染模板、数据包重载；分配 profile。

### A11 海龟蛋等非 POI 方块搜索分区剪枝 — 缺失核心使用点，P1 定点移植

- Lithium：`L/mixin/ai/non_poi_block_search/MoveToBlockGoalMixin.java:25` 原理：先查 section palette、缓存 chunk、跳过不可能存在目标的 section；`:79` 执行、`:100` 区分 chunk 已加载／保原顺序；`:145` 对分块结果用原搜索次序比较；`RemoveBlockGoalMixin.java:35` 只对可安全拆开的具体目标挂接。
- 当前：`N/.../ai/goal/MoveToBlockGoal.java:111` 仍四层坐标遍历 isValidTarget；RemoveBlockGoal 是具体接入点。大量僵尸/僵尸猪灵反复找海龟蛋是有针对性的候选场景。
- 设计：把“方块状态必要条件”与“完整目标条件”分开；palette 只能排除绝不可能的 section，不能以含有目标就断定位置可用。先做已加载 section 必要条件跳过，保留原 y/r/x/z 顺序和 Paper `mob.movingTarget`（MoveToBlockGoal.java:124）；不要把所有抽象 isValidTarget 当纯状态 predicate。已有 `BlockScanUtils`/`FilteredSectionCache` 可复用思想，但默认会 getChunk，必须保原来的加载策略。
- Folia：仅当前 owner 可访问范围，跨区域／未加载 chunk 沿原安全回退；不要为了预扫描主动加载新 chunk。保留方块破坏事件和取消语义。
- 验证：海龟蛋缺失、有多个等距候选、受限 home、最小/最大高度、section边缘、未加载边界；比较选中坐标、加载集合和插件事件，测 block-state reads 与完整循环次数。

### A12 猪灵／疣猪驱避物无匹配提前退出 — 部分已覆盖，P3

需要纠正“看到没 Lithium 注释即缺失”的误判：

- Lithium：`L/mixin/ai/non_poi_block_search/PiglinSpecificSensorMixin.java:33`、`HoglinSpecificSensorMixin.java:30` 先全范围无匹配判断再原搜索。
- 当前：`N/.../ai/sensing/PiglinSpecificSensor.java:111`、`HoglinSpecificSensor.java:60` 调用 findBlocksInBoxByManhattanDistance；`N/net/minecraft/world/level/blockscan/OrderedBlockMatcher.java:63` → `BlockScanUtils.java:122` → `FilteredSectionCache.java:45`，后者 `:51` 已用 maybeHas 排除无目标 section，并缓存 chunk section。
- 差额：当前仍逐坐标迭代，缓存只有 8 个槽；Lithium 全区域 pre-scan 在所有 section 都不含目标时可以连位置循环一起跳过。目标就在附近时预扫描反而增工作、甚至改变 chunk 加载时机。因此不是 P1 重写项目，应 profile 全空驱避区域再做负路径原型。
- 验证：相邻／无／远驱避物、熄灭灵魂营火、同曼哈顿距离顺序，载入范围不扩大；注意 tag reload。

### A13 方块状态级路径类型缓存 — 当前仅坐标缓存，P2

- Lithium：`L/mixin/ai/pathing/BlockStateBaseMixin.java:19,29` 初始化通用节点类型和邻接影响；动态箱体等返回 null fallback；`WalkNodeEvaluatorMixin.java:31` 命中省去 tag/属性链。
- 当前：`N/net/minecraft/world/level/pathfinder/PathTypeCache.java:8` 4096项坐标缓存，`:25` miss 调回 WalkNodeEvaluator；`WalkNodeEvaluator.java:514` 仍逐次分析状态，没有全局状态级结果；`PathfindingContext.java:19` 使用 server cache。不能把坐标缓存等价于 blockstate 缓存。
- 价值：重复遇到同类常见方块时，尤其不同坐标/多个路径/多个 region 的冷 miss；现有坐标缓存命中高时剩余收益下降。
- 设计：只缓存与世界/位置/方块实体无关的状态分类，动态状态回退；注册表/标签重载后安全重建并发布不可变数组/字段。不要照搬用异常驱动探测黑箱逻辑，源码端可明确拆出 pure-state 分类与 context-dependent 分类。
- 前置：当前 PathfindingContext 捕获 region PathTypeCache，异步 worker 可继续使用它；新实现必须隔离每次求解的可变坐标缓存／快照，不能把共享可变 region 缓存传播到线程池。见下方 R2。
- 验证：门开关、铁轨、流体、火/熔岩、蜂蜜、粉雪、活动潜影盒、标签 reload、空 chunk；全 blockstate 分类与原实现差分，真实寻路路径结果对比。

### A14 section 危险标记省掉路径邻居扫描 — 缺失，P2，依赖安全 section 追踪

- Lithium：`L/common/ai/pathing/PathNodeCache.java:42` 空 section/BlockStateFlags.PATH_NOT_OPEN 判定；`:55` 邻居都在同 section 时先做安全证明，`:78` early return；动态类型回退 `:117`。
- 当前：`N/.../pathfinder/WalkNodeEvaluator.java:485` 对邻域逐项取类型；FlyNodeEvaluator.java:309 同样调用，无 section 级危险证明。
- 设计：基于实际生效路径分类维护 conservative “可能包含影响邻域的状态”计数。只要有未知类型就不跳过；每次方块状态改变更新、载入重建、tag reload 重建；section边界始终回退/检查所有所需 section。避免引入比被省掉的读取更昂贵的全局锁。
- 异步：section 计数与区块内容要属于同一版本快照，不能先读“无危险”后另一线程刚放了火。纯数字原子化不等于数据一致性。
- 验证：section边缘、动态危险放置/移除、飞行/地面/大型体积实体、并发方块变更，比较危险结果与回退次数。

### A15 PathNavigationRegion 方块读取扁平化／直读 section — 部分缓存已有，P2

- Lithium：`L/mixin/ai/pathing/PathNavigationRegionMixin.java:49` 一维 chunks，`:78` 直读 section；getFluidState 从 blockstate 读取。
- 当前：`N/net/minecraft/world/level/PathNavigationRegion.java:29` 二维 chunk 引用缓存；`:47` getChunkNow；`:66` 未命中构造 EmptyLevelChunk；`:127` 和 `:137` 仍经 getChunk→chunk getter，没有对应直接 section 快路径。
- 价值：减少内层 getter、边界处理和缺失 chunk 的临时对象；收益须按已有 Paper/Moonrise getter JIT 行为实测。
- 设计：每次路径求解的不可变搜索视图，预计算 section范围和边界；保留当前空气/空流体/未加载 null 语义及 getBlockStateIfLoaded。注意“保存 ChunkAccess 引用”不等于内容快照；不要借扁平化继续扩大 worker 的实时世界访问。
- 验证：自定义世界高度、越界/未加载 chunk、路径区间边界、同步/异步一致性、任务取消/实体迁移。

### A16 活动导航监听集合 — Folia 区域分组已有，路径活动过滤缺失，P2

- Lithium：`L/mixin/entity/inactive_navigations/ServerLevelMixin.java:31` 仅通知有路径的 navigation；`:80` active/inactive 登记，`:100` 迭代 activeNavigations。
- 当前：`N/net/minecraft/server/level/ServerLevel.java:2008` 碰撞形状变化时，`:2024` 遍历当前 region 的 getNavigatingMobs；`:3307` 所有 Mob onTrackingStart 无条件登记。`PathNavigation.java:489` 大部分无路径实体最终 return false，所以仍有冗余遍历。
- 设计：在 RegionizedWorldData 加入当前真正可能响应 block update 的导航子集；最初保守用 path != null 即可，是否剔除 done/unprocessed/delayed 后续测量。必须跟随 setPath/stop、不同移动类型切换 navigation、实体增删、骑乘、HappyGhast控制变化；异步完成回调在 owner线程改变登记，不能 worker直接改集合。
- Folia 额外必做：`N/io/papermc/paper/threadedregions/RegionizedWorldData.java:101` merge、`:196` split 都显式搬现有 navigatingMobs，新集合必须同样迁移或从实体重建；世界传送／实体死亡不残留引用。
- 收益场景：同一地区很多静止怪物/村民同时发生大量活塞/方块更新；Paper 配置关闭 updatePathfindingOnBlockUpdate 时这项不产生收益。
- 验证：仍按原逻辑决定 shouldRecomputePath，逐条对比被通知导航；动态拆合region、并发路径完成、实体进出世界、无路径大量实体压力。

### A17 POI 搜索、存储索引、传送门查询 — 主机制已有，跳过整体移植；残余 P3

- Lithium：`L/mixin/ai/poi/PoiManagerMixin.java:79` 最近N，`:108` find，`:148`/`:202` 最近，`:55` 随机，`:239` 范围；`PoiSectionMixin.java:32` 单类型过滤直接查 map；`SectionStorageMixin.java` 非空section列位图；`fast_portals` 避免重复预加载；`tasks/AcquirePoiMixin.java:33` exact take 与最近N。
- 当前主机制证据：`N/.../ai/village/poi/PoiManager.java:239,253,280,298` 调 Moonrise 避免通用 Stream 查询；`:348,359,374` nearest；`:422` 收集后随机索引（无需洗牌）；`N/.../ai/behavior/AcquirePoi.java:97` 已直接 nearest 5；`N/net/minecraft/world/level/portal/PortalForcer.java:53` 已不预加载整个区域，`:55` findClosestPoiDataRecords 并保留 world border、地狱顶层、旧区块状态和低Y规则；`N/ca/spottedleaf/moonrise/patches/poi_lookup/PoiAccess.java:124,179,191` BFS/距离剪枝/空section/按类型分组，并在 `:227` 等处理等距顺序。当前 chunk-system 的 POI storage 生命周期也不同于 Lithium 通用 SectionStorage。
- 不声称实现逐行等价：Moonrise 的 BFS 与 Lithium 的有序附近迭代、column bitset 不同；但热调用上“避免全扫描/近端优先/N限制/不重复加载”的思想已存在。大面积替换会伤到已适配 Folia 的 owner 和 chunk lifecycle。
- 可研究的小差额：(1) 单一 PoiType 已知时直接 byType.get 的专用接口，当前 PoiAccess 仍迭代 type entries；(2) AcquirePoi 已找到 exact target 后使用直接坐标占位，当前 `AcquirePoi.java:122,135` 仍执行半径1 take 搜索；须原子检查类型/占用且验证 take成功才提交记忆；(3) 查询稀疏超高世界时 nonempty section bitset 的增量收益。三者先 profile，不要为了数量另造整套 POI 索引。
- Bukkit/Folia：守原加载顺序、搜索半径/等距规则、重试标记副作用、POI ticket原子性、占位与异步返回间状态变化。现有异步 AcquirePoi 回调必须重验实体 world/Brain/任务意图和 POI 是否仍存在可占用；单纯更快找到旧目标可能放大陈旧回调问题。
- 验证：多人/多村民竞争同一工作站、方块毁坏/重建、床/钟/工作站的等距结果、村民职业改变、区块卸载、跨维度同坐标、portal世界边界与高空顶层配置。

## 现有适配的前置风险（静态证据，不冒充运行复现）

### R1 跨世界共享的村庄距离缓存，应在扩展缓存前先修正

`N/net/minecraft/world/entity/ai/behavior/GoToClosestVillage.java:15` 是 static Long2ObjectOpenHashMap；`:31` key 只有 sectionPos.asLong；`:32-40` 普通读取、写入、removeIf，无锁。不同世界同坐标会共用缓存条目；不同 Folia region 并发读写同一个非线程安全 map。缓存时间还使用不同世界/region传入时间。建议先移除该附加缓存或改成与 world/region 生命周期一致、正确失效的结构，再考虑 Lithium 思想。当前底层 `PoiManager.java:59,67` 已有距离传播器并做 Folia 同步，所以不能仅加锁就宣布收益和语义均正确。

### R2 异步寻路输入和 region 坐标缓存需重新审视

`N/net/minecraft/world/level/pathfinder/PathFinder.java:69` prepare 时建立 context；`:88` 工作线程 processPath。`PathfindingContext.java:19` 捕获 `serverLevel.getPathTypeCache()`，`ServerLevel.java:3079` 返回当前 region的 pathTypesByPosCache。`PathTypeCache.java:11` 两个普通数组，`:25` 分两次写入坐标和类型；owner 线程在 `ServerLevel.java:2016` 也 invalidate。`PathNavigationRegion.java:47` 是实时 ChunkAccess 引用，没有内容快照。由此存在跨线程共享可变数据风险；尚未运行并发复现，不能写成已复现崩溃。新状态缓存/section危险跳过/直读section须在此边界之上明确 owner 或快照，不能单靠多一个 synchronized方法解决数据一致性。

### DAB、POI 降频与等价优化的关系

`N/.../npc/villager/Villager.java:265`、`N/.../animal/goat/Goat.java:180` 用 behaviorTick % activatedPriority 决定是否运行 Brain。它们会改变观察频率和时序，A1/A2 等减少单次成本可以叠加，但叠加收益不能相加。基准应至少分 DAB开/关、async开/关、POI降频开/关，并分别观察玩家附近与远处实体。当前 POI补丁还包括 ValidateNearbyPoi 的40tick间隔和 golem的距离/间隔策略，不能当成 Lithium 缓存思想等价覆盖或纳入同一收益归因。

建议执行顺序：先 R1/R2 所有权审视 → A1/A2小范围原型（分别开关、测命中）→ A11海龟蛋确定场景 → A7/A10小模块 → A16导航登记 → A13/A14/A15在明确异步快照设计后测量 → A8按实际动物场景 → A12/A17仅 profile支持才动。

## 补充：青蛙可攻击传感器的条件排序（P3）

`minimal_nonvanilla.ai.sensor.frog_attackables` 把廉价的 `Frog.canEat` 放在视线／可攻击判断之前。当前 `N/net/minecraft/world/entity/ai/sensing/FrogAttackablesSensor.java:18` 仍先做 `Sensor.isEntityAttackable`，所以有局部差额。不过参考模块明确声明：改变视线缓存首次求值时间，后续调用可能观察到不同的新鲜度。仅在密集青蛙／大量不可食用候选的 profile 显示热点后评估，默认不作为无语义变化的首批优化。验证目标选择、视线遮挡变化、缓存时序与插件目标事件；必要时保持原路径。
