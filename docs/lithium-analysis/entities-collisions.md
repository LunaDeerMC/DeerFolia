# Lithium 实体、碰撞、形状、爆炸及射线思想评估

本文为 `a81b44b` 的历史分析快照；后续实现及实测见[首批实施报告](../lithium-phase1-results.md)。

本篇是 [迁移价值总评估](../lithium-migration-analysis.md) 的源码核对明细。总体排期以总评估为准。路径以 DeerFolia 仓库根目录为起点；`Lithium/` 指 [固定参考源码](https://github.com/CaffeineMC/lithium/tree/416f68de780612d989d4bd8f1e95c5b2bb31aa97)，不是要求采用的实现版本。

只读源码审计；没有改服务端代码、运行构建或性能测试。基线 DeerFolia `a81b44b` / 26.3；Lithium develop `416f68de780612d989d4bd8f1e95c5b2bb31aa97` 只是用于定位思想，不作为移植版本限制。下文 NMS 路径前缀为 `DeerFolia-server/src/minecraft/java/`；Lithium 路径前缀为 `Lithium/common/src/main/java/net/caffeinemc/mods/lithium/`。

结论：实体侧最适合作为 P1 独立落地的是**空方块效果收集器快路径**；**非玩家装备变更追踪与无效附魔 tick 跳过**价值高，但实施列为 P2，先完成 ItemStack mutation 追踪基础设施。P2 还包括**按物品类别缩小掉落物合并候选集、按可碰撞类别缩小投射物候选集、地图墙重复工作合并**。Moonrise 的普通碰撞/形状/爆炸已采用大量相同思想，继续整套移植收益依据不足；静止实体跨 tick 方块缓存虽然有潜力，但必须先有可靠的 Folia 局部失效基础设施。

## 模块覆盖矩阵

| Lithium 模块/思想 | 当前覆盖证据 | 建议 |
|---|---|---|
| `entity.collisions.block_effects` 空收集器不 flush、延迟集合分配 | **缺失**。`InsideBlockEffectApplier.java:35` 构造时分配 EnumMap、每种 before/after 的 ArrayList、finalEffects；`:68` 每次空 flush 仍遍历全部 effect 类型 | **P1**，独立、小切口 |
| `entity.equipment_tracking.equipment_changes` 未改变装备不全槽深比较 | **缺失**。`EntityEquipment.java:31` 只有 map put；`LivingEntity.java:3596` 每次 collect；`:3623` 遍历所有槽并 `ItemStack.matches` | **高价值 / P2 实施**，依赖可靠 ItemStack 变更追踪，先非玩家 |
| `entity.equipment_tracking.enchantment_ticking` 没有 TICK 效果不遍历附魔 | **缺失**。`LivingEntity.java:470` 无条件 `EnchantmentHelper.tickEffects`；`EnchantmentHelper.java:158` 遍历全部槽；`:287` 分发 tick | **高价值 / P2 实施**，与装备追踪合并规划，独立开关/统计 |
| `experimental.entity.item_entity_merging` 按物品+组件+数量分桶 | **缺失此机制**。`ItemEntity.java:199` 已有 2/40 tick 合并频率；`:249` 仍扫附近所有 ItemEntity，再检查组件和数量。Moonrise 是实体 class/type 索引，不是 ItemStack 分类 | **P2**；密集混合掉落物更有价值 |
| `entity.projectile_projectile_collisions` 预先排除永远不能互相命中的投射物 | **缺失此分组**。`ProjectileUtil.java:170` 仍 `getEntities`；Moonrise `ChunkEntitySlices.java:50` 仅 all/hardColliding/class/type | **P2**；珍珠滞留仓、密集投射物 |
| `entity.framed_maps` 每张地图/观看玩家的重复遍历合并 | **部分：有帧更新间隔、只为 tracked players 更新，但仍嵌套遍历**。`ServerEntity.java:130-138` 对每个观察者调用；`MapItemSavedData.java:213` 每调用遍历所有持图玩家 | **P2**，地图墙/多人同图；不可照搬只处理当前玩家的 mixin |
| `entity.replace_entitytype_predicates` 直接使用类型查询 | **部分**。ArmorStand `:210` 已 `getEntitiesOfClass(AbstractMinecart.class)`；`OldMinecartBehavior.java:391` 仍全实体后 `instanceof AbstractMinecart` | **P1 小补充 / 低收益预期**；直接复用已有 Moonrise class 索引 |
| `entity.fast_retrieval` 小空间直接索引实体段 | **主要覆盖/架构替代**。Moonrise `EntityLookup.java:584/662/702`、`ChunkEntitySlices.java:50` 已按 chunk/section/type/class 查询 | 不建议替换实体存储 |
| `chunk.entity_class_groups` 任意实体类集合的延迟分组索引 | **部分覆盖**。Moonrise `ChunkEntitySlices.java:50-53` 有 hardColliding/class/type；`ChunkSystemEntity.java:16` hardColliding 将 Boat/Minecart/Shulker/HappyGhast 等合组，覆盖 Lithium 的主要硬碰撞消费场景；但没有通用任意 class group 索引 | 不单独迁移框架；**P2 按消费者需求扩展现有 Moonrise 索引**，如投射物可命中组，不复制 ClassInstanceMultiMap 体系 |
| `entity.collisions.intersection` 快速 block access、硬碰撞实体组、延迟实体查询 | **主要覆盖**。`Entity.java:1719` hard collision 查询；`CollisionUtil.java:1920` 局部 shape context 与 section 路径 | 不建议整套迁移，按具体残余热点测量 |
| `entity.collisions.movement` 特化单轴和向下移动 | **部分**。`Entity.java:1703` 已缩小单轴 AABB；`:1719` 仍先收集硬碰撞实体，没有 Lithium 的支撑方块完全阻止向下移动时立即返回 | **P2/P3 定向原型**，优先评估只向下且实际位移为零的支撑块快路径，不重写碰撞系统 |
| `entity.collisions.unpushable_cramming` 梯子内不能推动实体的额外索引 | **缺失但有明显语义差异**。`LivingEntity.java:3930` 有 early return 和碰撞数量上限；`:4117`、`EntitySelector.java:52` 受 Paper `fixClimbingBypassingCrammingRule`/Bukkit collidable 影响 | **P3**；不能默认把梯子实体移出 cramming 候选集 |
| `experimental.entity.block_caching.block_support` 静止实体支撑块跨 tick 缓存 | **缺失持续失效缓存**。`Entity.java:1156` 仍重新 `findSupportingBlock`；现有 `mainSupportingBlockPos` 不是变化订阅 | **P3**，待区段变化追踪可靠后做 |
| `experimental.entity.block_caching.suffocation` 静止实体窒息结果缓存 | **部分底层优化/缺跨 tick 缓存**。`Entity.java:3119` 已移除 stream、直接 section/palette、空 shape 跳过、AABB 快路径，但每次仍查世界 | **P3**，复用缓存失效基础设施，不替换已有快查询 |
| `shapes.blockstate_cache` 满方块判断缓存 | **覆盖**。`Block.java:365` 已直接 `moonrise$isFullBlock()`，甚至不走仍声明但未用的 Guava cache | 不迁移 |
| `shapes.optimized_matching` 简单 cuboid 相交快路径 | **覆盖**。`Shapes.java:226` 委托 `CollisionUtil.isJoinNonEmpty`；`VoxelShape.java:29/123` 保存 single AABB | 不迁移 |
| `shapes.precompute_shape_arrays` 预计算坐标数组 | **覆盖**。`VoxelShape.java:94` initCache 保存 rootCoordinates X/Y/Z；`CubeVoxelShape.java:10` 初始化缓存 | 不迁移 |
| `shapes.shape_merging` 合并坐标与体素快路径 | **覆盖**。`Shapes.java:219/223` 委托 CollisionUtil join；`VoxelShape.java:42/246` 还有 mergedORCache | 不迁移 |
| `shapes.specialized_shapes` 空/立方体特化 | **覆盖思想**。`VoxelShape.java:29/123` singleAABBRepresentation；无需复制 Lithium 新子类体系 | 不迁移 |
| `shapes.lazy_shape_context` 不使用装备/下蹲状态时不初始化 | **热路径覆盖，通用构造仍 eager**。`CollisionUtil.java:2141` LazyEntityCollisionContext、`:1920` 用于碰撞；`EntityCollisionContext.java:39` 通用构造仍读取 main hand | **P3 小补充**，先 profile 通用路径再考虑，不能称全局已覆盖 |
| `minimal_nonvanilla.collisions.empty_space` 直接构造空闲空间 bitset，减少 findFreePosition 的重复 shape 合并 | **部分覆盖/不同算法**。`Level.java:532` Moonrise 已去 stream、收窄收集范围、用更快多路 OR，`:578-584` 仍构造 union 再 difference；Lithium `common/shapes/VoxelShapeHelper.java:33` 直接构造最终 bitset，仍有独立差异 | **P3 原型、默认不建议启用**。地狱门密集传送/姿势检查才可能显著；已有 Moonrise 快实现，且 Lithium 明示 1e-7 边界会改变结果，不能认作完全等价 |
| `world.explosions.block_raycast` 爆炸射线复用 block/resistance/chunk | **覆盖**。`ServerExplosion.java:96-136` 多级缓存；`:395` 预计算射线；`:421` 复用同一 block | 不迁移 |
| `world.explosions.entity_raycast` 爆炸遮挡/曝光缓存 | **覆盖**。`ServerExplosion.java:141/198/205` 复用块与 collision shape，`:734` Folia region-local explosionDensityCache | 不迁移 |
| `world.explosions.block_raycast.skip_air` 不保留无用空气 | **部分/已有同思想**。`ServerExplosion.java:445` `fire || !blockState.isAir()`；Lithium 自己声明存在 wither rose 等行为差异 | 不扩展为空气计数/执行顺序整体改写；收益重叠、Bukkit 爆炸回调语义复杂 |
| `world.raycast` 射线局部 chunk/section 缓存、Fluid.NONE 快路径 | **部分**。`BlockGetter.java:88` 已只读已加载方块，`:95` 空气快路径，`:96` 复用流体；`:101` 仍计算 fluid shape；`Level.java:955` clipDirect 仍只是包装普通 clip | **P2**；保留不加载区块和 Bukkit predicate/捕获状态语义 |
| fast_elytra / fast_powder_snow / sprinting_particles | **已有 DeerFolia 专门移植**，当前 docs 和 0013 patch 明确列出 | 不重复 |

## 建议实现切口与关键验收

### 1. 方块效果收集器：首批 P1

- 思想：没有待处理效果时 O(1) 返回；某类 before/after 回调第一次使用时才分配列表。用 pending count/bit mask 表示非空，不能仅判断 EnumMap 是否 empty：预分配或曾使用过的 map 可能仍有空列表键。
- 保留 `advanceStep` 划分、`APPLY_ORDER`、before/effect/after 顺序、实体死亡后停止、lastStep 重置。
- Paper 26.3 与 Lithium 源码不同：effectsInStep 已是 `Map<Type, Consumer<Entity>>`，记录触发 BlockPos（`InsideBlockEffectApplier.java:98`），必须保留 recorded effect 的来源位置，避免火焰/熔岩 Bukkit 事件错误来源。
- 收集器实体私有，天然适合 Folia owner thread；不加 global pool/共享 MutableBlockPos。
- 验证：普通地面大量实体下测空 flush 次数、采样 CPU 与 bytes/entity；水/熔岩/火/细雪连续穿越、同一步多个 before/after、事件取消/杀实体场景逐项对比顺序/伤害/来源。

### 2. 非玩家装备变化追踪 + 附魔 ticking：高价值，P2 实施，基础设施要先做全

- 为 EntityEquipment 维护变更版本/dirty slots、是否有 tickable enchantment 的保守缓存；当无改动直接不做全槽 matches；无 TICK 附魔则跳过每 tick 全装备附魔遍历。
- **不能只 hook setItemSlot**。`ItemStack.java:969-1013` restore/set/remove/applyComponents、`:1306` setCount、`:577` durability 都能原地改变装备；CraftItemStack 镜像、插件修改、批量反序列化也必须通知。组件追踪可与漏斗、ItemEntity 索引复用，但按需订阅，避免给所有 ItemStack 写入无用热开销。
- 初期只支持非 Player；Lithium 当前也明确排除 Player，因为玩家装备来自背包与选中热键等不同链路（Lithium `equipment_changes/LivingEntityMixin.java:49`，`enchantment_ticking/...:26`）。未来扩展玩家另审。
- “不存在 TICK effect”不等于“没有 enchantment”；自定义数据包附魔必须按 effect component 判定，不能白名单硬编码；资源/registry reload 应失效缓存。
- 重入事件：`LivingEntity.java:3666` `EntityEquipmentChangedEvent`，`:3631` 玩家 armor event；回调中可能再次改装备。比较消费的版本与当前版本，不要在发包后盲目清空 dirty，导致丢事件/下一 tick 更新。
- 新 26.3 `SulfurCube.java:405` override collectEquipmentChanges 带 AI、属性、爆炸、伤害等状态重建。必须保护其自定义路径，不能在调用 override 之前粗暴截断，也要验证 BODY 槽与 archetype 切换。
- Entity 私有追踪随区域迁移，移除/换维度时解绑/重绑；不要按全服 tick 数比较 region local 状态，不引入全服锁。
- 验证：无装备/固定装备 1k~10k 非玩家、每 tick 换装对照；伤害耐久、计数变 0、同对象改组件、Bukkit EquipmentChangedEvent 修改装备、属性更新、发包、位置型附魔、数据包 TICK 效果、硫磺立方体行为；统计省略比较次数与真实 mutation/通知开销。

### 3. 掉落物合并候选分桶：P2

- 思想是在现有 Moonrise chunk/section 数据上给高密度 ItemEntity 建按 Item+components 分类、按数量范围区分的局部索引，仅选择可能装得下的一组。不能只用 Item（同种但组件不同不合并），也不能用可变 ItemStack 做裸 hash key；Lithium `common/entity/item/ItemEntityList.java:143-150` 复制 immutable key，`:37` 对高数量才启用。
- 只在密度达到阈值启用、低密度走现有线性扫描；阈值通过基准决定，别照搬 10。Paper 已降低静止掉落物合并频率，剩余收益需实测。
- 必须保持候选顺序、物品总量、目标实体选择、拾取延迟/所有者/寿命、Spigot 合并半径、Paper 横向合并/穿墙限制、`ItemMergeEvent` 取消与回调可修改 item 的行为。索引只减少绝不可能合并的候选，不聚合“批量一键合并”。
- 插件事件若改变尚未遍历候选的组件或引入新区段实体，快照及重入语义需要明确；必要时有监听器或变更后回退原扫描。
- 索引按 chunk section 随 Folia owner 迁移/卸载维护，实体迁移在原/新 owner 生命周期维护，不能世界级共享 map。
- 基准：不同密度、1/10/100 种 Item、多组件、半满/将满堆栈、取消 merge 的插件、频繁移动跨段；量测候选数量、组件比较次数、索引维护成本、内存、各 region p95/p99。

### 4. 投射物候选分类：P2

- 大量同类无法互相命中的珍珠仍被每个 projectile 的 broad phase 探测。把明确不可能命中的实体分组过滤在 spatial query 前；不要维护每次 query 的临时全量筛选结果。
- Moonrise 已有硬碰撞/class/type 分组，但“可被投射物命中”是不同语义，应在现有索引中扩展，不能套用 hardColliding 直接过滤。
- 仍对候选执行动态 `canHitEntity`、Bukkit 隐身/可见性（`Projectile.java:490`）、owner/vehicle、piercing、ProjectileHitEvent、龙部件；未知/自定义子类保守回退。
- 新版 `REDIRECTABLE_PROJECTILE` 标签及可反射火球等必须保留，不能断言所有 projectile 永远不互碰；Lithium 自身在 `ProjectileEntityClassGroups.java:73` 特判该标签。
- 26.3 还存在多实体命中路径 `ProjectileUtil.java:105`，应梳理所有入口，避免只改旧单命中函数。
- 基准：多组珍珠滞留仓与普通战斗；弓箭穿透、烟花、风弹/火球、潜影贝、末影龙、多 region、实体加入/移除；比较候选扫描数、命中顺序、事件数与物理结果。

### 5. 地图墙更新合并：P2

- 可减少“每个观察者调用一次，每次重新扫全体 map holders”的乘法项。当前 `MapItemSavedData.java:200` 是 synchronized，减少锁内重复工作还有利于并发 region。
- 不能原样移植 Lithium 的“frame 时只迭代 tickingPlayer”：26.3 `MapItemSavedData.java:229` 新增对其他玩家的 `MAP_INVISIBILITY_EQUIPMENT` 检查，若略过会破坏地图标记可见性；还要处理离线清理、共享地图、跨维度、Bukkit 自定义地图 renderer、Paper cursor limit/update interval。
- 更适合分离 map 全局一次性维护/帧标记与每个 owner region 的玩家数据更新，保留已有同步机制，传递必要快照；不在 frame region 中新增跨 region 玩家背包读取。
- 基准：1/25/100 同图观察者、地图墙/多个 frame 复用 map id、玩家装备隐藏标签、离线/换维度、自定义渲染；检查装饰/包结果与锁竞争。

### 6. 射线局部缓存与无流体快路径：P2

- 当前通用 clip 已跳空气、复用 BlockState 的 fluid，但每步仍走 getBlockStateIfLoaded 获取 chunk；可在单次 ray 内缓存当前 chunk/section，并在 Fluid.NONE 跳过 getFluidShape/clip。
- 缓存只活在一次调用，不需要跨 tick invalidation；不要把它升级到全 Level 共享 mutable 字段。必须保留 Paper 不加载缺失 chunk 的 miss 结果，不能复制 Lithium `world.getChunk()`。
- `Level.java:1013` getBlockStateIfLoaded 会尊重 captureTreeGeneration 的 capturedBlockStates；直读 section 会绕过它，需保留 capture 时原路径。Bukkit raytrace predicate 能产生副作用，可能变更块/区块/流体，缓存 chunk 的存活与回调位置都需保守处理。
- 非 ServerLevel 自定义 BlockGetter 回退现有路径；捕获/插件 predicate 情况可先不优化。
- 基准：普通 mob LOS/射箭、长距离插件 ray trace、流体模式 NONE/ANY/SOURCE、缺失 chunk、跨区域边界、world-border、捕获树生成与 predicate 修改世界；验证 hit type/pos/face 完全一致且无新增 chunk load。

### 7. 支撑方块快路径和持续 vicinity cache：分两期，P2/P3

- P2 可先验证“仅向下运动且支撑块把 Y 完全阻止”的单次调用快路径，减少所有 hard entity 与 block shape 列表构建；无需先上跨 tick 缓存。
- P3 再做静止实体 `supportingBlock`/`isInWall` 的跨 tick 结果复用，必须有 section revision/订阅，以实体 bounds、pose、dimension、gravity/碰撞上下文等为 key。静止足够久再启用，以免高移动实体与 setblock 负载退化。
- 动态 block entity 形状尤其重要：潜影盒打开可在不改变 BlockState 的情况下改变碰撞形状；Lithium suffocation mixin 自己在 `:89` 禁止缓存潜影盒。活塞、雪层、粉雪、脚手架、水流、蜂蜜/灵魂沙、插件 setblock、区段卸载和 region 拆分/合并都须失效。
- 保留 Moonrise `<1e-7` 边界、step-height 及 MC-310289 的高处实体检索修复（`Entity.java:1714`）；不整段替换为 Lithium movement 代码。
- 高风险库存/方块事件监听基础还没可靠落地时，这组不应先于独立 P1 项。

## 不建议的方向

- 整套替换 Moonrise collision/shape/entity lookup/explosion 实现：现有机制已覆盖主要思想，既增维护量又可能丢失上游碰撞修复。
- 把 `unpushable_cramming` 直接看作“梯子内实体不需要碰撞”：Paper 的 cramming 修复、Bukkit `setCollidable`、exemptions、scoreboard 是动态语义；Lithium 该模块自己还注释了非玩家鞘翅边界问题。
- 无条件跨 tick 缓存所有邻近方块：与动态 shape、插件事件及 Folia ownership 不相容。
- 直接把性能结果写成节省 xx%：当前仅完成静态审计；候选值得做原型不等于已经证明收益。

## 补充：class groups 与 empty space 的边界

- `chunk.entity_class_groups` 是优化基础设施，不是开关一开就产生额外收益。Lithium `mixin/chunk/entity_class_groups/ClassInstanceMultiMapMixin.java:30/62/76` 按需创建任意 group，并在 add/remove 维护。DeerFolia 不能照搬，因为实际实体访问是 Moonrise ChunkEntitySlices；已有 hardColliding 特化覆盖 boats/shulkers 的主要用途。若投射物基准显示候选分类值得，才按已证明的消费者增加一个 owner-local 索引，校验保持迭代顺序、动态 tag/custom subclass 回退、加入/删除/跨 region 迁移。成本是每次实体移动/增删多一组索引维护和内存，所以不应无条件泛化所有 predicates。
- `minimal_nonvanilla.collisions.empty_space` 针对门传送落点、可容纳实体的空位与 pose，而不是每 tick 常规移动碰撞。Lithium `VoxelShapeHelper.java:19-26` 明说 vanilla 合并时会丢弃 1e-7 内近邻坐标，它保留全部点，最终可能选到完全不同位置。Moonrise 当前 `Level.java:532-584` 已优化同目标但仍执行 OR+difference；是否直接 bitset 更快必须与现有 Moonrise 对比，不能与原版 stream 方案对比后宣称收益。验证需马穿门窒息修复、边界门、台阶/栅栏/活塞/潜影盒等动态 shape、不同尺寸/姿态、等距 tie-break、1e-7 邻近边界的属性测试；默认先保留既有行为。即便原型采用其直接构造思想，也应研究维持现有 epsilon 合并规则的算法，不接受“误差小所以无所谓”。

## 给主审计的核心事实

1. “已有 entity/block-state caches”不能推出已有 equipment dirty、item merging 分类或 stationary vicinity cache。这些是完全不同的键、生命周期与失效机制。
2. “已有碰撞优化”也不能推出所有 movement/raycast 思想全覆盖：Moonrise 已有单轴缩小 AABB，仍可评估支撑块短路；通用 raycast 仍有局部 chunk 与 Fluid.NONE 残余空间。
3. 装备/附魔追踪是本域最值得与漏斗的 ItemStack mutation 基础设施一起规划的系统性优化。实施先非玩家，再考虑玩家；保护 26.3 SulfurCube 新行为。

## 补充：无人观察时不统计爆炸空气

`world.explosions.block_raycast.skip_air.no_air_counting` 为避免无观察者时的空气统计，会调整爆炸执行与玩家遍历的时机。当前 `NMS/net/minecraft/world/level/ServerExplosion.java:445` 已跳过不需要的空气，`:625` 先执行爆炸并于 `:655` 返回 toBlow.size，且有 Bukkit 事件和区域化结果缓存。它不是应单独搬入的通用统计快路径；默认跳过整套执行顺序改写。如果 profile 后确有粒子统计热点，只做保留当前回调／伤害／发包时序的局部方案。
