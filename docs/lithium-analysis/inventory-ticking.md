# Lithium 库存、漏斗与方块实体优化迁移审查

本文为 `a81b44b` 的历史分析快照；后续实现及实测见[首批实施报告](../lithium-phase1-results.md)。

本篇是 [迁移价值总评估](../lithium-migration-analysis.md) 的源码核对明细。总体排期以总评估为准。路径以 DeerFolia 仓库根目录为起点；`Lithium/` 指 [固定参考源码](https://github.com/CaffeineMC/lithium/tree/416f68de780612d989d4bd8f1e95c5b2bb31aa97)，不是要求采用的实现版本。

只读源码分析，2026-10-15；没有修改服务端实现、编译或运行性能基准。DeerFolia 基线为 `a81b44b`（26.3.x），Lithium 样本为 develop `416f68de780612d989d4bd8f1e95c5b2bb31aa97`。以下优先级是工程判断，收益是场景预期，不是已测数据。采纳的是避免重复计算、完整失效通知和局部状态机的思想，不以照搬某版本代码为目标。

路径简称：

- `D/` = `DeerFolia-server/src/minecraft/java/`
- `B/` = `paper-server/src/main/java/`
- `L/` = `Lithium/common/src/main/java/net/caffeinemc/mods/lithium/`

## 核心结论

此领域确有较大的、目前未覆盖的机制空间。Paper 的漏斗优化不是 Lithium 漏斗体系的等价替代：当前仍会在可搬运 tick 上扫描槽位、寻找上下游容器/实体并尝试搬运；Paper 已有局部循环优化、事件短路、满箱 cooldown、槽位数组缓存等。Lithium 进一步记录“上一次失败之后条件有没有改变”，据此省掉整次工作，并在可以证明无可观察副作用时停止 ticking。

优先做保留 Folia 区域身份的休眠框架，以及合成器、空闲营火、已关闭潜影盒、锁定漏斗这些唤醒条件较少的对象。熔炉/酿造台也值得做，但先只覆盖能证明不产生事件的空闲态。漏斗完整缓存/休眠应排在库存变更追踪、插件语义和 Moonrise 实体变更追踪之后，不能当作一份可直接拷贝的小优化。

普通箱子和末影箱盖子动画休眠是客户端优化，服务端不该列作欠缺优化。两种箱子的服务端 `getTicker` 已返回 null（D `ChestBlock.java:328`、`EnderChestBlock.java:105`）。

## 逐机制清单

状态中“缺失”指实际调用路径没有该机制，不是仅因没有 Lithium 类名。P1=近期适合原型/实现，P2=依赖与验证条件成熟后，P3=低优先或仅在 profile 证明后。

| 机制 / Lithium 模块 | DeerFolia 当前覆盖 | 价值与典型场景 | 复杂度 / 建议 |
|---|---|---|---|
| 堆肥桶 sided slot 数组复用 `alloc.composter` | **已移植**，DeerFolia 条件启用共享 ZERO/EMPTY | 堆肥桶顶漏斗，减少短命数组 | 已有；跳过重复实现。D `ComposterBlock:295,335,394`；L `mixin/alloc/composter/ComposterMixin:17` |
| 漏斗单次扫描/拷贝/事件开销 `block.hopper` 的局部部分 | **部分且已有较多 Paper 实现**：合并空满判断、copy 延后、无监听器省 pickup/search 事件、事件访问标记、槽位数组缓存 | 常规搬运 | 不整体替换；保留 Paper 行为，仅针对 profile 的残余热点。D `HopperBlockEntity:34,160,233,328,539,654` |
| 库存版本号、occupied/full 槽位计数 `LithiumStackList` | **缺失**；NonNullList，`getFullState` 每次遍历，`ItemStack.setCount` 只是赋值 | 大型仓储/分类系统，后续全部缓存的基础 | 高；**P1 建基础与验证，P2 启用广泛消费者**。L `common/hopper/LithiumStackList:24,103,116,247`；D `HopperBlockEntity:37,160`、`ItemStack:1306` |
| ItemStack 数量/类型/组件变更通知 `util.item_component_and_count_tracking` | **缺失**；Bukkit 可直接镜像修改底层 stack | 保证版本缓存可靠，也可复用到装备追踪 | 高；作为基础值得做，惰性订阅，未被订阅的 stack 仅快速分支。L `.../ItemStackMixin:38,112,136`、`PatchedDataComponentMapMixin:32` |
| 库存替换/卸载/移除通知 `util.inventory_change_listening` | **缺失**；普通 `setChanged` 主要负责保存与 comparator，不提供可重用订阅协议 | NBT/命令/插件替换容器后安全失效；防引用泄漏 | 高；**P1 基础 / P2 消费**。L `BaseContainerBlockEntityMixin:24,35,40,81` |
| 上下游固定容器/无容器结果缓存 | **缺失**；每次 `getAttachedContainer/getSourceContainer/getContainerAt` | 空闲漏斗、满箱漏斗；减少重复方块和 BE 查询 | 中高；**P2**。必须尊重 `HopperInventorySearchEvent`，双箱变化、方块更新、卸载、朝向与插件替换。L `HopperBlockEntityMixin:506,544`；D `HopperBlockEntity:654,671,703` |
| 相同双方版本的失败搬运复用 | **缺失**；Paper cooldown 只是降低重试频率，不知道状态未变 | 堵塞分类器、空源库存、无法插入的组合 | 高；**P2，高预期价值**。不能缓存事件取消/动态插件判断；还要保留现有失败 comparator 副作用。L `HopperBlockEntityMixin:156,239,277`；D `HopperBlockEntity:279,314` |
| 比较器输出强度缓存 | **缺失**；当前每次遍历库存算强度 | 高频读取相同大箱/容器的红石装置 | 中（依赖追踪）；**P2**。计算采用 26.3 当前公式，不能搬旧公式。L `LithiumStackList:188`；D `AbstractContainerMenu:905` |
| 失败取物 comparator 更新模式缓存/邻近 comparator 追踪 | **缺失**；Paper 失败 pull 会 `updatedContainer.setChanged()` | 为失败重试短路、休眠保持红石兼容 | 高；**P2 的正确性依赖**，不是可以省略的细节。L `LithiumStackList:234`、`HopperBlockEntityMixin:787`、`util/inventory_comparator_tracking` |
| 双箱联合版本/缓存适配 `LithiumDoubleInventory/StackList` | **缺失**；有 CompoundContainer 功能但无联合 revision | 双箱占比高的仓储 | 中高；**P2 依赖项**，不用追求照搬包装类。两半版本、分合/替换/卸载都应失效 |
| 无物品实体/库存实体查询结果复用；移动通知 | **缺失此机制**；实体查询本身已有 Moonrise 加速 | 大量未被实体覆盖的漏斗，静止掉落物搬运失败时 | 很高；**P2**。接 Moonrise 的对象/切片生命周期，不能装回 Lithium 原版 EntitySection 体系。L `HopperBlockEntityMixin:363,393`；D `HopperBlockEntity:688,732` |
| 完整事件驱动漏斗休眠 `block.hopper` | **缺失**；无 cooldown 时每 tick 继续执行 | 长时间空闲、堵塞且无 comparator 可观察副作用的漏斗 | 很高；**P2，依赖上面所有机制**。L `HopperBlockEntityMixin:761` |
| 红石锁定漏斗休眠 `sleeping.hopper` | **缺失**；锁定时仍减 cooldown/调用 tryMoveItems | 长期锁定的漏斗仓储 | 中；**P1**，先覆盖 cooldown 已耗尽，保留当前配置时序，独立于完整库存追踪。L `.../sleeping/hopper/HopperBlockEntityMixin:34`；D `HopperBlockEntity:141,199` |
| 熔炉/高炉/烟熏炉空闲休眠 `sleeping.furnace` | **缺失** | 大量闲置熔炉、冶炼阵列 | 中高；**P1 保守空闲子集，P2 扩展**。先从无燃烧、无冷却进度且缺少必要输入的确定性态做，不能把事件取消当稳定失败。L `.../furnace/AbstractFurnaceBlockEntityMixin:71`；D `AbstractFurnaceBlockEntity:186` |
| 酿造台空闲休眠 `sleeping.brewing_stand` | **缺失** | 酿造阵列，闲置设备 | 中高；**P1 保守子集 / P2 扩展**。不能单以 brewTime==0 证明未来没事，燃料事件/配方修改须纳入。L `.../brewing_stand/BrewingStandBlockEntityMixin:64`；D `BrewingStandBlockEntity:166` |
| 点燃但空、熄灭且进度归零的营火休眠 `sleeping.campfire.lit/unlit` | **缺失**；4 槽/4 进度每 tick 扫 | 装饰性营火多、闲置营火烹饪阵列 | 中；**P1**，需 Bukkit 修改物品/进度、NBT、点燃熄灭唤醒；不能将 `stopCooking` 冻结当通用无副作用。D `CampfireBlockEntity:64,117,218` |
| 合成器倒计时结束休眠 `sleeping.crafter` | **缺失**；0 时仍 tick 算 -1 | 自动合成系统、摆放大量合成器 | 低中；**P1，适合第一块验证框架**。`setCraftingTicksRemaining`、NBT/插件、方块状态重绑唤醒。D `CrafterBlockEntity:274,284`；L `.../crafter/CrafterBlockEntityMixin:55` |
| 潜影盒关闭且动画完全结束后休眠 `sleeping.shulker_box` | **缺失**；关闭态仍 updateAnimation | 大量放置的潜影盒仓储 | 中；**P1**。开关事件唤醒、保留打开过程挤实体/邻居更新。D `ShulkerBoxBlockEntity:103,148,177`；L `.../shulker_box/ShulkerBoxBlockEntityMixin:53,63` |
| 幽匿催发体无扩散 cursor 休眠 `sleeping.sculk_catalyst` | **缺失**；serverTick 调 spreader | 大面积闲置催发体/农场 | 中；**P2**，规模普遍性弱于前三者。保留 game-event listener，加入 cursor 唤醒。D `SculkCatalystBlockEntity:45`；L `.../sculk_catalyst/SculkCatalystBlockEntityMixin:52,57` |
| 幽匿感测体/校频感测体/尖啸体无待处理振动休眠 `sleeping.sculk_sensor_shrieker` | **缺失**；服务端仍执行 VibrationSystem.Ticker.tick | 大面积幽匿、振动装置 | 中高；**P2**。不可只查 currentVibration，selection queue 也须空，监听仍工作；NBT/候选排期唤醒。D `SculkSensorBlock:163`、`CalibratedSculkSensorBlock:36`、`SculkShriekerBlock:137` |
| 普通/末影箱盖子动画休眠 `sleeping.chest_animation` | **不适用服务端** | 客户端动画 CPU | **跳过**。D `ChestBlock:329`、`EnderChestBlock:106` |
| 重用同区块 shouldTick 判断 `chunk_tickable` | **部分**：Moonrise 已把判断改为 holder.isTickingReady，但每 ticker 仍查 | 同 chunk 放置大量 BE | 中；**P2/P3 按 profile**。已有每 8 BE midTickTasks，期间状态可变，缓存生命周期不能机械覆盖全循环。D `Level:1557,1560`、`ServerLevel:1019`；L `.../chunk_tickable/LevelMixin:29` |
| BE 世界边界检查缓存 `world_border` | **缺失**；isTicking 每次查边界 | BE 极密且边界稳定 | 中高；**P3**。应复用线程安全边界 generation/snapshot，动态缩放正确失效，不为每个 BE 向共享普通 List 注册回调。D `LevelChunk:528`、`WorldBorder:30,50,257`；L `.../world_border/DirectBlockEntityTickInvokerMixin:49` |
| BE 支持方块状态/类型校验缓存 `minimal_nonvanilla...support_cache` | **缺失**；每 tick 重新取 state、isValid | BE 调度占 profile 很高时 | 中高；**P3/暂缓**。Lithium 自己标 nonVanillaBehavior；当前有 Paper 无效 BE 删除语义，不能抹掉。D `LevelChunk:968`；L `.../support_cache/package-info.java:1` |
| ticker 表改 fastutil `collections.block_entity_tickers` | **缺失**；当前 Maps.newHashMap | BE 创建/重绑/卸载与内存 | 低；**P3**。不等于减少逐 tick 工作；顺序/遍历语义和内存实测后决定。D `LevelChunk:94`；L `.../block_entity_tickers/LevelChunkMixin:35` |
| 不加载区块读取现有 BE `util.block_entity_retrieval` | **已有可组合底层能力，缺 Lithium 接口** | 给邻居失效通知/缓存提供安全查询 | 低中；作为依赖复用 Moonrise loaded-chunk 查询与 CHECK，不新增平行世界查询体系。D `Level:1734` 会走 IMMEDIATE；`LevelChunk:473` 有 CHECK；L `.../block_entity_retrieval/LevelMixin:35` 的单 main-thread 判断不能移用 |

## 必须面对的兼容边界

### Folia 区域与生命周期

1. **禁止直接复制 Lithium 的无位置睡眠 sentinel。** L `common/block/entity/SleepingBlockEntity.java:18` 返回 null；D `RegionizedWorldData.java:236`、`:247` split 对每个 ticker 的 `getPos()` 直接取 x/z，无法处理 null。merge 在 `:114`、`:121` 通过 `getTileEntity()` 调整时间。新的睡眠包装必须一直保留真实位置、BE 引用、移除状态与类型，才能安全 split/merge/unload。
2. Lithium `sleeping.hopper/HopperBlockEntityMixin:85` 用 `Long.MAX_VALUE` 表示睡眠中的 tickedGameTime；D `HopperBlockEntity:78` merge 会加 redstone offset。该 sentinel 会参与正常时间运算，存在溢出/错误排序，不应沿用。采用显式 sleeping 状态与“本 region tick 已/未执行”的相位标记；需要 deadline 时用 Folia redstone clock 并在 updateTicks 调整，不能使用全局 gameTime。
3. 现有漏斗不是固定 8/7 tick：D `HopperBlockEntity:626` 使用 `hopperTransfer - skipTickCount`，`:149` 使用 `hopperCheck`；搬运数量来自 `hopperAmount`（`:245`）。Lithium hardcode 单件与 7/8 cooldown 不能直接继承。
4. 所有缓存和订阅属于对象的 region/owner；同步邻居通知只能在当前允许访问的拥有区域内发生。实体迁移、区块 unload/reload、region split/merge 必须撤销/重建订阅或转移状态；不要在 global map 上存可以随时被另一区域修改的 BE/ItemStack 引用。
5. 不要用 `ConcurrentHashMap` 代替所有权设计。它只能保护 map，无法使容器槽位、Bukkit 事件和实体查询跨区域安全。无法证明当前拥有全部依赖时走现有查询/搬运路径，并让接收区域负责唤醒。
6. 不要重建 Lithium 的原版 EntitySection 数据结构。D `ca/.../EntityLookup.java:983` 的 onMove 只有跨 section 时才继续回调，`:987` 无切片变化就返回；漏斗 AABB 可在同一 section 内被进入/离开，因此仅挂 section-change 钩子会漏通知。需截获 tracked entity 的实际位移/包围盒、加入/移除、可访问状态变化、物品堆合并/数量/组件变化。静止掉落物数量/类型变化也可能改变可拾取性。
7. BE 逐 tick 调度含 Folia profiler、每 8 个 ticked BE 执行 Moonrise midTickTasks。休眠筛选应定义对该节奏的影响，保留调度器公平性；先保留 list 中稳定包装并快速跳过，再实测是否值得做独立 active list。

### Bukkit / Paper 语义

- **搜索事件不能被缓存吞掉。** `HopperInventorySearchEvent` 每次查找可返回不同虚拟库存，即使相邻方块没变（D `HopperBlockEntity:637`）。保守方案：相关监听存在时保持当前事件频率与重新取结果；未知自定义容器不参加失败缓存/睡眠。
- **搬运/拾取取消不是稳定失败。** `InventoryMoveItemEvent` 或 `InventoryPickupItemEvent` 的插件可依赖外部权限/时间，每次结果不同。不能在库存没变时永久跳过。启用相关监听时保留当前尝试语义，或仅对不触发这些事件的确定性空态休眠。监听器运行期注册/卸载需要 generation 机制使已有睡眠对象退出到兼容路径，不能只在最初 sleep 时查一次。
- **追踪 setChanged 不够。** B `CraftInventory:52` 返回 NMS ItemStack 的 Bukkit mirror，B `CraftItemStack:234` 直接 `setCount`，`:208` 非空 type 修改走 NMS `setItem`，`:453` `setItemMeta` 还会 restore/apply component patch。D `ItemStack:1247` 替换整个 `PatchedDataComponentMap`。因此要覆盖数量、物品类型、组件 set/remove/patch/restore、组件容器替换与槽位列表替换；组件可能改变合并条件、最大堆叠和燃料行为。
- **容器 max-stack-size 也可变。** D `HopperBlockEntity:71` 和 `BrewingStandBlockEntity:128` 暴露 setter；应使 fullness、signal 与失败记录失效，不能只记录 ItemStack 变化。
- **大箱必须按两半 identity+revision 管理**，破坏、旋转、重新合并、另一半区块状态变化使关联失效，不能永久缓存当时新建的 CompoundContainer。
- **保留 Paper 事件/堆叠/配置处理。** 当前支持 `hopperAmount` 大于 1、容器自定义 max、Paper event 镜像/替换、ignoreOccludingBlocks、cooldownWhenFull。应把新的缓存封装在现有语义外围，而不是替换为 Lithium 单件 transfer 实现。
- **红石副作用以当前 DeerFolia 为真值。** 当前 `hopperPull` 失败仍 `setChanged`（`:314`），先比较基线的实际 comparator 更新序列；不要按 Lithium 原版猜测。更明显的是 D `AbstractContainerMenu:905` 现在用 `Mth.lerpDiscrete(totalPercent,0,15)`；L `LithiumStackList:204` 还是 `floor(f*14)+(非空?1:0)`，这就是只借鉴“缓存输出”的思想、必须保留新版本语义的实例。
- **熔炉/酿造台事件重试。** D `AbstractFurnaceBlockEntity:214` 燃料事件可被取消且直接 return，D `BrewingStandBlockEntity:172` 同样如此；下一 tick 当前代码会再触发。不能因无进度便永久睡眠；不燃烧也可能还有配方/外部燃料组件条件要重算。配方注册、删除/重载与燃料规则变化须使相关缓存无效，或者保守地不睡这些状态。
- **插件直接改工作进度也要唤醒。** B `CraftFurnace:49,61`、`CraftBrewingStand:40,61`、`CraftCampfire:35,44` 可直接赋底层字段/列表。B `CraftBlockEntityState:45` `useSnapshot=false` 让 snapshot 直接就是 live BE，因此只钩 `loadAdditional/setChanged` 不足以涵盖无需 snapshot update 的 API。需要在 live setter 确保唤醒；普通 snapshot 更新则由 `update:187`、NBT copy、setChanged 覆盖。

## 建议实现层次

1. **休眠基础先做得小。** 为 ticker 增加 active/sleeping 状态和稳定身份，sleep 是“当前逻辑无工作”状态，不删除 BE、不移除事件监听、不改变持久化。强制唤醒入口：方块状态重绑、NBT/data apply、相关 live API setter、对应事件。初版保留 region 原有 ticker list，测试 split/merge 稳定后再评估移出 active iteration 是否划算。
2. **先上合成器/潜影盒/空营火/锁定漏斗。** 每类列出可睡条件、所有能改变该条件的入口、唤醒相位。空闲熔炉/酿造台以更保守条件跟进。开关应独立于其他 Lithium 小优化，能够快速回退，并提供睡眠数/唤醒数/原因计数帮助观察。
3. **建立可共享的修订号与一次性订阅机制。** InventoryIdentity + list generation + content revision + capacity revision；ItemStack 按需订阅数量/类型/组件变化，双箱合并两半 revision。遵循 region 单写者，不给所有容器加全局锁；每次通知后清理一次性 listener，防止长期保留对象。先让 counters 在影子模式和完整扫描结果比对，不立即跳过行为。
4. **渐进放大漏斗快路径。** 先固定原生 BE 的 identity/空满/信号缓存；再无插件动态事件条件下的重复失败缓存；最后实体空查询缓存与完整休眠。每步都有 fallback，未知容器、监听器影响和不完整追踪不能被当成“没变化”。
5. **实体变更接现有 Moonrise。** 给被观察的实体切片惰性加对应类别 revision/订阅；每次相关实体移动/生命周期/物品内容变更更新。优先用单调 revision 而不是跨区域 gameTime 比较；迁移使订阅失效重建，避免两个 region 时钟直接比较。先 measure 热点，再决定是否需要去重 tracker 对象。
6. **缓存的 correctness contract 明确化。** “没有变化”必须指所有影响结果与副作用的输入；除内容外还包括方块状态、方向可插入规则、容器 capacity、配置/配方 generation、插件监听语义和邻近 comparator 可观察性。遇到未知输入不缓存。

## 有针对性的验证与基准

所有性能候选都应对比同一提交的开关开/关，使用相同 world、JVM、玩家轨迹、region threads、负载布局，充分预热。至少记录各 region 的 p50/p95/p99 tick 时长、BE 子计时/调用数、每秒分配、GC、CPU、缓存命中率/失效率/活跃订阅数、吞吐和唤醒延迟；不要只报总 TPS。要同时测一处密集与分散多 region，空闲与持续活跃。没有 profile 热点时，基础追踪开销可能大于缓存节省。

| 候选 | 基准工作负载 | 正确性重点 |
|---|---|---|
| BE 休眠 | 1k/10k 同类 BE 的全闲置、全工作、1%/10%活动混合；批量放置卸载 | 精确工作完成 tick、物品守恒、保存重启、拆分合并、区块卸载/加载、睡眠中破坏/更换；插件 live setter/NBT/数据包重载唤醒 |
| 锁定漏斗 | 长锁阵列、短周期红石解锁、相邻漏斗双向不同 tick 顺序 | `hopper-transfer/check/amount` 多组配置；7/8/自定义 cooldown 基线一致，锁定中接收物品和解锁当 tick 不多搬/漏搬 |
| 库存 revision | 高搬运仓储与少写多读仓储分别运行 | 全部 set/remove/grow/shrink、Bukkit mirror setAmount/setType/setMeta/data component、list replacement、setMaxStackSize；影子扫描对照 full/empty/signal |
| 固定容器缓存 | 空箱/满箱/双箱/堆肥桶/熔炉/潜影盒混合 | 朝向变化、活塞/替换、另一半大箱卸载/移除，search event 每次替换目标/返回 null，保存重载后引用不可残留 |
| 失败搬运复用 | 不能合并、目标满、方向槽位拒绝、输出堵塞 | move/pickup event 计数与顺序、取消后放行、事件替换 item、事件内再修改库存；当前基线 comparator 更新与红石脉冲轨迹 |
| 实体查询缓存 | 空漏斗上空、静止不适配掉落物、连续掉落物、移动库存矿车/箱船 | 同 section 小范围进入 AABB（不是只跨 chunk）、出生/消失/合并、掉落物数量/组件变化、跨区域迁移和 unload/reload；多库存实体的随机选择不能固定成一个 |
| 幽匿休眠 | 大片无事件、稀疏事件、事件风暴 | 候选尚未变 current 的振动不能被睡掉；传播时延/频率/校频侧输入/尖啸效果/催发扩散和 Bukkit 事件顺序 |
| 辅助 ticker 缓存 | 高 BE 密度但实体都活跃，才能看出单项检查收益 | midTickTasks 中状态改变、动态世界边界与中心变化、无效 BE 清理、区块 readiness 与暂停 tick 模式 |

验收门槛：只在与优化关闭基线的可观察行为（物品、时序、事件、红石、持久化）一致且目标负载有稳定改善、持续活跃负载无显著回退之后默认启用。源码分析可以确认“哪些重复工作可被条件性省掉”，不能确认现实服务器收益大小。
