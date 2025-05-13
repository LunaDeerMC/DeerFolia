# DeerFolia

DeerFolia 是一个基于 [Folia](https://papermc.io/software/folia) 的 Minecraft 服务器核心，它们是由 [Mojang](https://mojang.com) 的 Minecraft 服务器核心修改而来。

而 Folia 是由  [Paper](https://papermc.io/software/paper) 修改而来，Paper 核心修复了很多原版 Minecraft 的有趣特性，由于 Folia 直接继承自 Paper，因此也同时继承了这些消失的特性。这也是为什么很多服主选择了 Purpur，不过 Purpur 官方已经明确表示不会基于 Folia 开发一个新的具备原版特性的分支。

## 本分支相比于其他 Folia 分支有何特点？

### 1. 最小修改：在还原原版机制的基础上保持对 Folia 及上游 Paper 相关补丁的最小修改，避免潜在的意外bug；

### 2. 最少配置：尽可能减少自定义的配置，减轻了部署负担，不用面对复杂的配置内容；

### 3. 性能优化：在 Folia 基础上引入更多的优化，进步一提升单个 region 的性能；

## 此分支特性

- 还原了 [刷沙机制](deer-folia-server/minecraft-patches/features/0003-Allow-sand-duplication.patch)（虽然 paper 已支持刷沙，但由于 folia 的特性，paper 的刷沙开关在 folia 是无效的）；
- [Dynamic Activation of Brain (Pufferfish)](deer-folia-server/minecraft-patches/features/0004-Dynamic-Activation-Brain.patch)；
- [Async Path Finding (Pufferfish)](deer-folia-server/minecraft-patches/features/0005-Async-pathfinding.patch)；
- [Kaiiju Entity Throttling (Kaiiju)](deer-folia-server/minecraft-patches/features/0007-Kaiiju-Entity-Throttling.patch)；
- [Kaiiju Skip Empty Listeners Event](deer-folia-server/paper-patches/features/0002-Kaiiju-Skip-Empty-Listeners-Event.patch)；
- ~~还原了 [虚空交易](https://ssl.lunadeer.cn:14446/zhangyuheng/DeerFolia/src/branch/master/patches/server/0002-Allow-void-trading.patch)~~（疑似已被 mojang 官方修复）；
- ~~还原了 [刷线机制](https://ssl.lunadeer.cn:14446/zhangyuheng/DeerFolia/src/branch/master/patches/server/0004-Allow-tripwire-duplication.patch)~~（mojang已在1.21.3修复）；

## 额外配置

### Dynamic Activation of Brain

通过动态调整实体的激活频率，减少了不必要的计算，提升了服务器的性能。

```yaml
# config/deer-folia.yml
dynamic-activation-brain:
   enabled: true
   start-distance: 12
   activation-distance-mod: 8
   maximum-activation-prio: 20
```
- `enabled`：是否启用；
- `activation-distance-mod`：递减倍数；
- `maximum-activation-prio`：最大停顿（如果设置为20则表示无论多远，每20tick都至少计算一次）；
- `start-distance`：开始递减的距离；

> 如果刷怪塔收到影响可以尝试减少 `activation-distance-mod` 或者增加 `start-distance`。
> 通常情况下，`activation-distance-mod` 推荐设置为 7 或 8，`start-distance` 推荐设置为 12。

### Async Path Finding

引入异步路径查找和相关的机制，提升了路径查找的性能和响应速度。

```yaml
# config/deer-folia.yml
async-pathfinding:
   enabled: true
   async-pathfinding-keep-alive: 60
   async-pathfinding-max-threads: 20
```

- `enabled`：是否启用；
- `async-pathfinding-keepalive`：单个线程最大活跃时间；
- `async-pathfinding-max-threads`：用于异步寻路的线程池大小；

> 线程池本质上为虚拟线程，与cpu物理核心无关。过少的线程池大小可能会对性能提升不明显，过多通常不会有明显的负面影响但是可能会影响java的GC。
> 推荐 `async-pathfinding-max-threads` 为 10-20 之间。

### Kaiiju Entity Throttling

引入了 Kaiiju 的实体节流机制，提升了服务器的性能。

```yaml
# config/deer-folia.yml
kaiiju-entity-throttling: true

# config/kaiiju-entity-throttling.yml
ZombifiedPiglin:
   limit: 1000
   removal: 3000
```

- `kaiiju-entity-throttling`：是否启用 Kaiiju 实体节流机制；
- `limit`：实体数量限制，超过该数量后实体每 3 tick 才会被更新一次；
- `removal`：实体移除限制，超过该数量后实体最先生成的实体会被逐步移除；

## 如何自行编译

1. 克隆本仓库到本地；
2. 在终端执行 `./gradlew applyAllPatches` 应用补丁；
3. 完成后会在项目目录下生成 `deer-folia-server` 和 `deer-folia-api` ，前者即为源码目录;
4. 执行 `./gradlew createMojmapPaperclipJar` ，完成后会在 `deer-folia-server/build/libs` 下生成服务器核心文件；

## 如何添加新补丁

### 对上游代码修改

1. 修改 `deer-folia-server` 或 `deer-folia-api` 中的源码；
2. 在 `deer-folia-server` 或 `deer-folia-api` 目录中将修改内容添加 `git add .` 并提交 `git commit` ，填写补丁信息；
3. 在根目录运行 `./gradlew rebuildAllServerPatches` ，将刚才提交的修改生成为新补丁；

### 新增文件

1. 在 `deer-folia-server/src/main/java` 中新增相关文件；
2. 直接提交 `git add .` 并提交 `git commit` ，填写补丁信息即可；

> 通过将与上游源码无关的新增文件独立开，减少对上游修改 patch 文件的长度使得项目更易于维护。

## 修改已有补丁

修改已有的补丁步骤相对复杂：

### 方法一

这种方法的工作原理是暂时将 `HEAD` 重置为所需的提交，然后使用 `git rebase` 进行编辑。

> ❗ 在编辑过程中，除非您 *同时* 将对应模块重置为相关提交，否则将无法编译。就 API 而言，您必须重新应用 Server 补丁，如果正在编辑
> Server 补丁，则必须重新应用 API 补丁。还要注意的是，这样做时任何一个模块都可能无法编译。这不是一个正常的现象，但这种情况时有发生。请给
> Paper 官方提交 ISSUE ！

1. 在 `deer-folia-server` 或 `deer-folia-api` 目录中执行 `git rebase -i base`
   ，应该会输出 [这样的](https://gist.github.com/zachbr/21e92993cb99f62ffd7905d7b02f3159) 内容。
2. 将你需要修改的补丁由 `pick` 替换为 `edit` 然后保存退出；
   - 一次只能修改 **一个** 文件！
3. 对你需要修改的补丁作出新的修改；
4. 使用 `git add .` 添加补丁，再使用 `git commit --amend` 提交；
   - **确保添加了 `--amend` 选项** 否则将会创建一个新补丁而不是修改原补丁。
   - 此处提交时也可以修改补丁信息。
5. 终端执行 `git rebase --continue` 应用更新；
6. 再在跟项目目录执行 `./gradlew rebuildPatches` 生成新的补丁；

### 方法二

如果你只是在编辑一个较新的提交，或者你的改动很小，那么在 HEAD 上进行改动，然后在测试后移动提交可能会更简单。

这种方法的好处是可以编译测试你的改动，而不必弄乱你的 HEAD。

#### 手动

1. 修改相应位置源码；
2. 提交修改（可以不写提交内容）；
3. 在 `deer-folia-server` 或 `deer-folia-api` 目录中执行 `git rebase -i base` ，将刚才的提交移动到你想要修改的补丁提交下方；
4. 将新提交的 `pick` 修改为如下内容：
   - `f`/`fixup`：将你的新修改合并到补丁内，但不改变补丁信息；
   - `s`/`squash`：将你的新修改合并到补丁内，并用新的补丁信息替换原补丁信息；
5. 在跟项目目录执行 `./gradlew rebuildPatches` 应用补丁更新；

#### 自动

1. 修改相应位置源码；
2. 提交修改内容 `git commit -a --fixup <要修改的补丁 hash 值>`；
   - 如果希望更新补丁信息，你可以使用 `--squash` 替换 `--fixup`；
   - 如果你不知道要修改的补丁 hash 值，你可以使用 `git log` 查看；
   - 如果你只知道补丁的名称，你可以使用 `git log --grep=<补丁名称>` 查看；
3. 执行 `git rebase -i --autosquash base` ，这将会自动将你的修改移动到对应的补丁下方；
4. 在跟项目目录执行 `./gradlew rebuildAllServerPatches` 应用补丁更新；

## 更新上游 Folia 修改

1. 在终端执行 `./gradlew updateFoliaRef` 更新上游 Folia 修改；
2. 应用更新的补丁：`./gradlew applyAllPatches`。
3. 如果存在冲突，解决冲突后执行 `git add .` 将解决完的文件添加到暂存区；
   - 如果遇到 `invalid object` 错误，可以使用 `git apply --reject <patch file>` 手动应用补丁；
   - 会生成 `.rej` 文件，可在其中查看冲突内容，手动解决冲突；
   - 完成后删除 `.rej` 文件，然后执行 `git add .`；
4. 然后运行 `git am --resolved` 继续应用补丁；
5. 如果存在新的冲突，重复步骤 3 和 4；
6. 全部补丁应用完成后，更新补丁：`./gradlew rebuildAllServerPatches`。
