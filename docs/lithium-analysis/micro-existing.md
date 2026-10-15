# Lithium 其他模块与现有移植复核（只读静态分析）

本文为 `a81b44b` 的历史分析快照；后续实现及实测见[首批实施报告](../lithium-phase1-results.md)。

本篇是 [迁移价值总评估](../lithium-migration-analysis.md) 的源码核对明细。总体排期以总评估为准。路径以 DeerFolia 仓库根目录为起点；`Lithium/` 指 [固定参考源码](https://github.com/CaffeineMC/lithium/tree/416f68de780612d989d4bd8f1e95c5b2bb31aa97)，不是要求采用的实现版本。

基线：Lithium develop `416f68de780612d989d4bd8f1e95c5b2bb31aa97`；DeerFolia `a81b44b` / 26.3.x。版本只是定位证据；推荐按机制、当前剩余工作量和 Folia 的所有权来判断。未运行性能基准，未改代码，任何收益和疑似语义差异均待验证。

路径简称：L = `Lithium/common/src/main/java/net/caffeinemc/mods/lithium/`；D = `DeerFolia-server/src/minecraft/java/net/minecraft/`。

## 按模块的全清单（本组范围）

| 模块 | 优化思想 | 当前覆盖证据 | 建议 |
|---|---|---|---|
| alloc.chunk_random | 可复用临时随机位置，仅调用未知代码时冻结为不可变位置 | D server/level/ServerLevel.java:1065–1104 已按随机 tick 索引跳过无效候选，仅真正 tick 时分配 BlockPos；1097 明示调用方可能保存位置。D world/level/Level.java:2201 改为 Folia 线程随机源。 | 不移植原实现；随机 tick 热路径已由 Moonrise 更换。剩余天气分配仅 P3 调查；绝不可使用 Lithium 单个 per-level mutable pos / randValue。 |
| alloc.composter | 缓存固定槽位数组，避免漏斗查询每次 new int[] | D world/level/block/ComposterBlock.java:296,336,395 已移植 | 已有，保留并核验分配；数组只读约定。 |
| alloc.deep_passengers | 递归 stream 改为一次遍历收集 | D world/entity/Entity.java:552,560,5771–5810 已移植，Paper 原先已优化空乘客路径和一些遍历 | 已有；多层骑乘、spawn/unload/teleport 中遍历顺序与回调变更需验证，不能宣称全部零分配。 |
| alloc.entity_iteration | 直接迭代实体列表，省包装迭代器 | D world/level/Level.java:1855–1920 主要实体查询走 Moonrise EntityLookup，而非原版 EntitySection | 不优先：旧类仍存在不代表主热路径未优化；确认真实 profile 命中才考虑。 |
| alloc.entity_tracker | identity fastutil set 减内存和遍历成本 | D server/level/ChunkMap.java:1252 已用 ReferenceOpenHashSet | 已由 Paper 覆盖，不重复。 |
| alloc.enum_values.piston_block | Direction.values 的数组缓存 | D world/level/block/piston/PistonBaseBlock.java:118 等已移植 | 已有，JFR 证实目标调用分配/收益；JIT 可能消除部分数组。 |
| alloc.enum_values.piston_handler | 同上，活塞结构搜索 | D world/level/block/piston/PistonStructureResolver.java:178 已移植 | 已有。 |
| alloc.enum_values.redstone_wire | 同上，红石邻居更新 | D world/level/block/RedstoneWireBlock.java:346,378 已移植 | 已有；不等于已移植 redstone_wire 功率计算机制。 |
| alloc.explosion_behavior | 复用 Optional，去掉 map/lambda 包装 | D world/level/EntityBasedExplosionDamageCalculator.java:20 已移植；底层爆炸缓存进一步影响命中频率 | 已有，收益取决于有效调用量和逃逸分析；验证 source 的特殊阻力逻辑。 |
| alloc.nbt | fastutil backing map，减 entry 开销 | D nbt/CompoundTag.java:54,168,396 已覆盖构造、反序列化和 copy | 已由 Paper 覆盖。 |
| collections.attributes | 身份集合、较小初始容量 | D world/entity/ai/attributes/AttributeMap.java:16–42 已移植 | 已有但微小；原 AttributeInstance 未覆写 equals/hashCode，原 ObjectOpenHashSet 也已经按对象身份比较，不能夸大为消除大量值比较；容量与布局是更可信候选收益。 |
| collections.chunk_tickets | removeIf 单遍压缩数组，避免 O(n²) 多次移动 | D util/SortedArraySet.java:19–49 已实现，且清理尾部引用 | Moonrise 覆盖，不重复。 |
| collections.entity_by_type | Class -> list 使用 identity map | D util/ClassInstanceMultiMap.java:16 仍为 HashMap，但实际实体查询走 Moonrise（见上） | 路径已替换；不为补齐清单去优化旧结构。 |
| collections.entity_filtering | 仅首次建立某类型列表时做类型验证，循环替代 stream | 同上，D Level.java:1855–1920 | 路径已替换；若其他少量调用出现实测热点再判断。不能照抄放松 API 类型校验。 |
| collections.entity_ticking | clone 当前表代替逐项复制，保障迭代中增删 | D world/level/entity/EntityTickList.java:12–40 使用 IteratorSafeOrderedReferenceSet；ServerLevel.java:200 的全服列表被 Folia 注释替代 | Moonrise/Folia 更换底层结构，不应恢复 Lithium 原双 map 算法。 |
| collections.mob_spawning（membership） | weighted list 额外哈希索引，contains O(1) | D world/level/biome/MobSpawnSettings.java:88–112 Paper WeightedSpawnerDataList 有 HashSet+contains | 部分覆盖：Builder 路径有；CODEC 在同文件:43–48 使用通用 WeightedList.codec，不自动使用子类。应检查实际注册表加载路径是否仍命中通用线性 contains。需数据验证；P2 小范围补足（不要全局改所有 WeightedList 的 equality 或不可变性）。 |
| collections.mob_spawning（category map） | enum keys 直接索引避免通用 hash | D MobSpawnSettings.java:63 最终 ImmutableMap；Builder:166 收集 ImmutableMap，不能把 Builder:89 的 EnumMap 说成最终结构已覆盖 | 未全面覆盖；P3 低成本基准候选，可用不可变 enum 索引，保留缺项与空列表区别、definedCategories 不可变性和数据包行为。 |
| math.fast_blockpos | 常量方向位移内联，Direction offset 字段直取 | D core/BlockPos.java:143–221、core/Direction.java:66–70,130–134,306–317 已覆盖 | Paper 覆盖，避免进一步盲目 switch 重写；Lithium 自己文档也警告部分 switch 生成更差机器码（L mixin/math/fast_blockpos/BlockPosMixin.java:9–18）。 |
| math.fast_util | opposite 直取、随机方向缓存数组、轴循环与 AABB 直接分支 | D Direction.java:225、365 已覆盖；AxisCycle.java:37、69 和 world/phys/AABB.java:83 已由 DeerFolia 移植 | 已覆盖；需测现有配置检查是否抵消小函数优化。 |
| math.sine_lut | 三角函数查表用对称性压缩 256KiB 到约64KiB，改善 cache，保持结果 | D util/Mth.java:35,50–55 仍 65536 float 表 | P3；当前 CPU cache 和 JDK25 下可能更慢，先 JMH + 实服。不能沿用上游宣传收益。详见下方数值边界。 |
| cached_hashcode | 缓存流体遮挡 key 的 identity hash | D world/level/material/FlowingFluid.java:256–293 使用 Moonrise 线程局部数组 cache + 预计算状态/方向 ID；旧 BlockStatePairKey 仍存在但不在此热路 | 路径被取代；不优化遗留 key。 |
| block.flatten_states | FluidState.isEmpty 预计算字段 | D world/level/material/FluidState.java:28,59,74 已有；Moonrise 还缓存 source/height/amount/ticking | Paper/Moonrise 覆盖。 |
| block.moving_block_shapes | 缓存有限的活塞位移与伸缩组合碰撞形状，减少 move/union/optimize | D world/level/block/piston/PistonMovingBlockEntity.java:364–391 仍每次 move+Shapes.or；D world/phys/shapes/VoxelShape.java:570–588 仍 new ArrayVoxelShape。Moonrise 已优化基础形状/OR，但未覆盖活塞离散位移 memoization | **P2 候选**：具体且仍有剩余，活塞密集+实体碰撞场景测试后决定。详见下文。 |
| block.redstone_wire | 同一次功率计算合并邻居读，复用导体判断，保持更新语义 | D world/level/redstone/DefaultRedstoneWireEvaluator.java:47–49 仍 block/wire 分开；RedstoneWireEvaluator.java:32–42 仍重复上方读取和导体判断。Paper 已提供 EIGENCRAFT/ALTERNATE_CURRENT，不能据此说默认 evaluator 已覆盖 | **P2 候选**：先做局部读复用，谨慎合并；按各模式分别测试。详见下文。 |
| profiler | 无 profiler 时跳过 ThreadLocal；两线程字段 cache | D util/profiling/Profiler.java:11,51–56 原版26.3已有 ACTIVE_COUNT 快路，并保留 Tracy | 主要思想已覆盖；不搬两线程 cache。Folia 多 tick 线程，结构不合；现实现保留 Tracy 语义。 |
| startup（NeoForge） | 客户端 mod 初始化失败时仍触发 Lithium initializer | neoforge/.../mixin/startup/MinecraftMixin.java:13–19 | 非服务端性能机制，排除。 |
| client_tick | 客户端实体/粒子/AI无用工作跳过 | common .../mixin/client_tick/package-info.java:1 | 专用服务端排除。服务端跳过 sprint 粒子这一思想已经单独移植，不等于要加载客户端 mixin。 |
| compat.worldedit | 批量直接写块时补发漏斗缓存失效 | fabric/.../mixin/compat/worldedit/LevelChunkMixin.java:29–54 | 不是独立提速项，但**不能完全忽略思想**：若引入漏斗/库存缓存，要把 Bukkit/WorldEdit/FAWE 等绕过常规邻居更新的变更纳入失效验证；不直接搬 Fabric 兼容层。 |
| debug.palette | 调试 palette 一致性 | common .../mixin/debug/package-info.java 默认 enabled=false | 非生产提速项，排除；可吸收验证思路。 |

## 候选设计与验证重点

### 活塞形状 memoization（P2）

L mixin/block/moving_block_shapes/PistonMovingBlockEntityMixin.java:54–93 把标准 offset 限定在 0/0.5/1，特殊 offset 回退；102–138 预计算六方向三阶段伸缩形状。VoxelShapeMixin.java:11–25 用 volatile 数组的 copy-on-write 安全发布，并非必然单线程不安全。

DeerFolia 应优先评估局部、有限缓存（例如每个移动活塞实体最近阶段或只缓存固定头/底座组合），或严格以 immutable source shape identity 为 key 的有界共享缓存，而不是无条件给所有 VoxelShape 加13格懒缓存。动态 shape、世界/位置相关 shape、非标准进度都应回退，且遵守 Folia 所有权；缓存本身共享时必须安全发布。Moonrise 已显著优化 shape 算法，增量收益需要实际测量。

验证：六方向伸出/缩回、粘性/普通、蜂蜜/黏液、非整块动态碰撞形状、NOCLIP 分支、实体位移、方块保存重载和跨区边界；记录计算次数、分配率、缓存命中和驻留内存，避免只测空载活塞。

### 保持行为的红石读取复用（P2）

L common/block/redstone/RedstoneWirePowerCalculations.java:12–52 明确区分改变传播更新算法与只优化功率计算；74 起合并 neighbor 状态访问。上游最多百分比是特定装置结果，不能转述为 DeerFolia 预期收益。

可以先仅缓存同调用的 abovePos / aboveState / conductor 结果，再比较合并 block 与 wire 功率计算。保留 Bukkit BlockRedstoneEvent 的次数、参数和回调变更生效；不删邻居更新，不改传播顺序。D RedstoneWireBlock.java:335–339 的 shouldSignal 已是 Folia region data，不能还原成全局 static/block 字段。若依赖 direct chunk 访问，区边界不能强行读/加载其他线程拥有的 chunk。

验证：VANILLA、EIGENCRAFT（:303–305 同样调用 default evaluator）、ALTERNATE_CURRENT、experimental evaluator 分开；红石楼梯/透明块/强弱充能/更新顺序敏感装置/活塞/插件修改电流/区域边界。观察实际 getBlockState 和 isRedstoneConductor 次数，而非只测平均TPS。

### MobSpawnSettings 缺口（P2/P3）

Paper builder 提供哈希 membership，但 registry CODEC 走通用 WeightedList（含线性 contains），需通过运行时采样或加载真实 worldgen 配置确认使用何种实现，不能只看补丁标题就认为全面覆盖。不要把现今 SpawnerData record（D MobSpawnSettings.java:183）或 Weighted record 改成 identity equality，Lithium 旧注释“vanilla 都是引用相等”已不适用于整个26.3泛型类型。最小设计是只在高频且不可变的 spawn list 边界维护 equals 语义的附加 set，并保留重复元素、顺序和随机权重。category map 属更低优先级，基准短列表/缺项/数据包自定义情况。

### CompactSineLUT（P3）

L common/util/math/CompactSineLUT.java:13–24 自己承认 cache 命中足够时额外运算可更慢。移植思想可接受，照搬数值转换不可接受：D Mth.java:50–55 是 double→long→mask，Lithium CompactSineLUT.java:59–65 是 double→int→mask，大幅值输入会不等价。要保留当前转换，包括 NaN、±Infinity、±0、long/int 饱和边界。验证所有65536离散表项 raw float bits（原 Lithium 校验用 ==，不证明 ±0 位一致）及真实输入域。用 JDK25 完整 warmup + fork、冷热cache、多区线程实际工作负载评估；192KiB表节约不等于显著堆收益。

## 对现有16项/17类的复核结论

现有16项只是文档分组，不能计算成 Lithium 的性能覆盖率。主要是降低单次成本，并未建立 hopper/equipment/brain 等增量失效系统。当前静态阅读未在本范围确认需立即修复的生产错误；以下是值得补证的假设，不能当成已复现 bug：

- **AABB/AxisCycle、enum values、Optional、AttributeMap**：真正运行代码还有两个可变配置字段读取；JIT 内联、逃逸分析和单/多态分派会改变收益。必须测带配置开关的最终代码，不用原 Lithium microbenchmark 替代。AttributeInstance 没有值 equals/hashCode，身份集合变化较小。
- **深层乘客**：DeerFolia 的 postorder 递归是明确每层先子再父，保持顺序值得肯定；但由惰性 stream 变为 eager snapshot 后，遍历回调修改骑乘树的时间点可能不同。应测试含事件回调的 add/remove/unload/teleport、多层乘客和短路 anyMatch，而非只测普通双层骑乘。
- **共享槽位/方向数组**：final 仅固定引用，不固定内容。当前消费者只读通常安全，应审计调用点并保持只读；不是需要线程锁的理由。未来不能把同一数组交给会重排/修改的代码。
- **海平面缓存**：NoiseBasedChunkGenerator.java:71、504 是非volatile lazy int；immutable settings 下竞争至多重复计算通常同值，不能据此宣称已发生线程错误。要明确 registry holder 绑定后不再变更的生命周期，测试异步生成与插件自定义generator，收益也可能小于配置/分支成本。
- **Raid bossbar**：Raid.java:295–303 在 tick开头刷新 dirty，644 延迟更新。明确从即时更新变为合并后的时序，跨tick可有一tick延迟；验证 raid wave/restart/stop、同tick多次伤害和 Folia ownsRaid 前提。不是纯数学等价替换。可由AI/实体组深入复核。
- **GateBehavior**：GateBehavior.java:83–94 将最终状态检查融合到逐项循环，隐含后续 behavior 不会改变先前 behavior 运行状态；需检查共享行为对象/插件自定义AI。禁用时仍做 hasRunningBehavior 累计和额外 getStatus，影响最严格的开关基准。仅静态待验证，不宣称存在原版错误。
- **TemptingSensor**：doTick:47 使用 getLocalPlayers，距离相同保留首个（<），且事件仍在选中后触发；重点回归平距、无穷/非法坐标是否可进入、事件取消/改目标与区域迁移。
- **实体glide/frost/sprint、天气/冻水/流体检查**：从代码看保留新26.3结构/标签和主要事件；需在对应组验证可观察事件与动态world查询。流体重排依赖检查只读及当前owner线程，不能扩展成跨tick缓存。

不建议为清单数量继续堆叠微优化；可先投在仍会做大量重复工作的系统级机制，再用基准决定这些低成本增量项。

## 补充：POI 类型表的身份 Map（P3）

Fabric 的 `collections.poi_types` 只把 BlockState→PoiType 的 backing map 换为 reference fastutil map。当前 `D world/entity/ai/village/poi/PoiTypes.java:59` 是 HashMap，`:92`／`:96` 分别 get／containsKey，故这项具体集合替换未覆盖；它与 Moonrise 的 POI 空间索引是不同层次。可在注册结束后安全发布、维持当前 Holder 值和注册行为的前提下做低优先基准，但不能把它算成另一套高价值 POI 搜索优化。仅参考思想，不搬 Fabric 的类描述符和旧字段类型。
