# CorpseLab API 用法

附属模组的统一使用文档，对应当前实现。

- [基础用法](#基础用法)
- [模型、骨架与复活目标](#模型骨架与复活目标)
- [生命周期事件](#生命周期事件)

## 基础用法

### 准备

```java
import com.pyrostella.corpselab.api.Corpses;
import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.RevivalProblem;
```

下文 `entity` 是已知的尸体或其肢体实体，`source` 是来源活体（`LivingEntity`），`player` 是操作玩家。

修改操作在**服务端主线程**调用。普通服务端事件回调中可直接调用；客户端和异步任务不能直接修改尸体。失败返回 `false` 或空 `Optional`。

### 最常用的操作

```java
Corpses.revive(entity);             // 开始复活，默认 40 tick（约两秒）
Corpses.revive(entity, 60);         // 自定义复活时长，单位为 tick
Corpses.stopRevival(entity);        // 中止复活，保留尸体
Corpses.tryGrab(entity, player);    // 按玩家视线抓取指定尸体的肢体
Corpses.releaseGrab(entity);        // 释放这具尸体当前的抓取关系
```

以上都返回 `boolean`，不关心结果时可以直接调用。需要后续处理时：

```java
if (Corpses.revive(entity)) {
    // 已开始复活，可以扣除道具或开始施法效果
}
```

`true` 表示动画成功开始，最终复活仍可能失败。成功时监听 `CorpseReviveEvent.Complete`，中断时监听 `CorpseReviveEvent.Interrupted`。

- 生物尸体支持复活，玩家尸体目前不支持。
- 抓取仍受距离、视线选取和配置限制，不能抢走已被抓住的尸体。
- `releaseGrab(entity)` 是服务端强制释放接口，不检查当前抓取者是不是调用者。

### 只检查能否复活

```java
boolean ready = Corpses.canRevive(entity);
```

不必先调用它才能复活；直接调用 `revive()` 已包含检查。

需要给玩家显示失败说明时，才使用可选诊断：

```java
if (!Corpses.revive(entity)) {
    Corpses.revivalProblem(entity).ifPresent(problem -> {
        if (problem == RevivalProblem.UUID_IN_USE) {
            // 按枚举处理冲突，显示什么提示由附属决定
        }
    });
}
```

诊断返回 `Optional<RevivalProblem>`，不是字符串或文本组件。当前可复活时为空，存在问题时可直接比较枚举。它不记录上一次操作结果，也不预测事件否决或检查自定义时长、目标姿态。

枚举包括：`NOT_CORPSE`、`SERVER_ONLY`、`MAIN_THREAD`、`REMOVED`、`UNSUPPORTED`、`ALREADY_REVIVING`、`MISSING_DATA`、`UUID_IN_USE`、`INVALID_ENTITY`。

### 查询一具尸体

```java
Corpses.find(entity).ifPresent(corpse -> {
    var sourceType = corpse.sourceType(); // 来源实体类型的注册 ID
    int age = corpse.ageTicks();         // 尸体年龄，单位为 tick
    boolean lootable = corpse.supportsLooting();
});
```

普通实体返回空 `Optional`；传入肢体会自动找到所属尸体。`find()` 不会搜索附近实体。

| 查询 | 含义 |
|---|---|
| `corpse.entity()` | 尸体实体本身 |
| `corpse.kind()` | 玩家尸体或生物尸体 |
| `corpse.sourceType()` | 来源实体类型 ID |
| `corpse.sourceId()` | 来源实体 UUID，可能不存在 |
| `corpse.ownerId()` | 玩家尸体所有者 UUID，可能不存在 |
| `corpse.ageTicks()` | 尸体年龄，复活期间暂停计时 |
| `corpse.state()` | 普通、冻结、抓取、复活、移除等状态 |
| `corpse.supportsRevival()` | 这类尸体是否支持复活，不代表现在就能复活 |
| `corpse.supportsLooting()` | 这类尸体是否支持搜刮，不会打开搜刮界面 |

视图跟随实体变化；精确玩法查询应在服务端线程进行。当前部件、抓取和复活进度只提供服务端数据，客户端分别返回空集合或空 `Optional`，未同步的模拟状态为 `UNKNOWN`。

### 查询抓取和复活进度

```java
Corpses.find(entity).ifPresent(corpse -> {
    corpse.grab().ifPresent(grab -> {
        var grabber = grab.playerId(); // 抓取者 UUID
        int part = grab.partIndex();  // 被抓取部件的下标
    });
    corpse.revival().ifPresent(revival -> {
        double progress = revival.progress(); // 时间进度，范围 0～1
    });
});
```

不在抓取或复活状态时，对应 `Optional` 为空。时间进度不是动画的缓动曲线。

### 创建尸体

```java
var created = Corpses.create(source);
created.ifPresent(corpse -> {
    var newEntity = corpse.entity();
});
```

创建成功返回尸体视图，失败返回空。创建不会杀死来源实体，死亡时模组已自动创建尸体，不要在死亡监听中再创建一遍。

**创建玩家尸体会转移背包物品**，`keepInventory` 开启时不转移。生成失败不会转移物品。

生物需要已有骨架数据，否则创建失败。需要自定义初速度时：

```java
Corpses.create(source, new net.minecraft.world.phys.Vec3(0, 3, 0));
```

速度单位为格/秒。已有 `BodyPlan plan` 的模型适配方还可以显式指定骨架：

```java
Corpses.create(source, plan, net.minecraft.world.phys.Vec3.ZERO);
```

这项重载主要用于生物；玩家使用内置骨架。

### 读取部件（按需使用）

```java
Corpses.find(entity).ifPresent(corpse -> {
    for (var part : corpse.parts()) {
        String id = part.id();          // 同一骨架内稳定的部件标识
        var position = part.position(); // 世界坐标
        var rotation = part.rotation(); // 四元数 x/y/z/w
        var halfSize = part.halfExtents();
        float scale = part.scale();     // 复活长回动画的缩放
    }
});
```

`parts()` 返回不可修改的快照，不会随着物理模拟自动更新；下一次查询取得新快照。`basePartIndex()` 返回当前复活基准的下标；未进入复活时默认选最大部件，没有可用部件时为 `-1`。

## 模型、骨架与复活目标

### 最常用：换基准和目标姿态

```java
import com.pyrostella.corpselab.api.*;

Corpses.revive(entity, RevivalOptions.defaults()
    .base("body")
    .duration(60)
    .pose(targetPose));
```

`targetPose` 是 `RevivalPose`。只想换基准时，省略 `.pose(...)` 即可。

- `base` 使用**物理部件的完整路径**，对应 `CorpseView.Part.id()`。它不是所有模型统一的名称。
- 没指定基准时仍选最大部件；空控制骨骼没有物理 AABB，不能直接作为基准。
- 距离分类改用指定基准：部件 AABB 离它不超过两格则归位，其余长回。
- 未指定的目标部件保持原有默认目标。未知部件路径会使复活返回 `false`。
- 提供器只在开始时执行一次，采样结果随复活进度保存，重载后继续执行。
- 目标控制**尸体归位的最终姿态**。恢复为活体后，动画由原实体模组继续控制。

### 手写目标姿态

```java
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

var targetPose = RevivalPose.empty()
    .bone("body", new Vec3(0, 0.8, 0), new Quaterniond().rotateX(0.4))
    .bone("body.arm", new Vec3(0.5, 0.9, 0), new Quaterniond().rotateZ(1.2));
```

位置是部件中心的身体局部坐标，脚底为原点、Y 向上，单位为格。库负责按尸体朝向转到世界中。

旋转使用单位四元数、角度为弧度。第四个参数可指定均匀缩放：

```java
var targetPose = RevivalPose.empty()
    .bone("body", new Vec3(0, 1, 0), new Quaterniond(), 0.8f);
```

当前支持位置、旋转和正的均匀缩放。非均匀缩放和镜像不支持，内建动画采样工具会明确拒绝，不会静默丢弃。

### 外部实现动画状态

```java
RevivalTarget target = (corpse, skeleton) -> {
    // 可以按来源、实体状态或自己的动画系统生成目标
    return RevivalPose.rest(skeleton)
        .bone("body.arm", new Vec3(0.5, 1.1, 0), new Quaterniond().rotateZ(1.2));
};

Corpses.revive(entity, RevivalOptions.defaults().target(target));
```

也可以用类实现 `RevivalTarget`，只需实现一个方法：

```java
RevivalPose sample(CorpseView corpse, BodyPlan skeleton);
```

自定义动画系统只需要输出每个部件的最终姿态，不需要操作物理求解器。

### 原版 Java 关键帧动画

在客户端，用已有的 `HierarchicalModel<?> model` 和 `AnimationDefinition animation`：

```java
import com.pyrostella.corpselab.api.client.VanillaRevivalPoses;

var targetPose = VanillaRevivalPoses.firstFrame(model, animation);
```

库将模型重置后，用原版 `KeyframeAnimations` 在 **0 毫秒**采样，并恢复模型原来的位置、旋转和缩放。不会播放整段动画。

非关键帧、由 Java 代码直接设置模型的动画，也可采样：

```java
var targetPose = VanillaRevivalPoses.capture(model, () -> {
    model.root().getChild("body").xRot = 0.5f;
});
```

这里针对原版层级模型。其他模型体系通过 `RevivalTarget` 提供数据。

### GeckoLib 动画

在安装 GeckoLib 的客户端，传入已烘焙模型与已烘焙动画：

```java
import com.pyrostella.corpselab.api.client.GeckoRevivalPoses;

var targetPose = GeckoRevivalPoses.firstFrame(bakedGeoModel, animation);
```

参数类型是 GeckoLib 的 `BakedGeoModel` 和 `core.animation.Animation`，不是 `RawAnimation` 的播放指令。采样时间为零，包含父骨骼变换，空控制骨骼的变换会传递给几何部件，不修改共享模型。

包含 Molang 表达式的动画会在调用时求值，实体相关查询上下文需由适配方准备。

**两端使用方式：**这些模型和动画工具只在客户端使用。服务端执行复活时需要的是采样后的 `RevivalPose`，不能把客户端模型类放进服务端代码。

```java
var data = targetPose.save();           // CompoundTag，可供附属保存或传输
var serverPose = RevivalPose.load(data); // 服务端还原数据
```

当前不自动传输动画定义。附属可以预先生成目标姿态，或通过自己的数据流程将采样结果交给服务端，再调用 `Corpses.revive(...)`。服务器应决定采用哪一份目标姿态。

### 自定义物理骨架

```java
var skeleton = CorpseSkeletons.builder()
    .box("body", new Vec3(0, 1, 0), new Vec3(0.25, 0.4, 0.2), 4)
    .box("arm", new Vec3(0.4, 1, 0), new Vec3(0.1, 0.3, 0.1), 1)
    .joint("arm", "body")
    .build();

Corpses.create(source, skeleton, Vec3.ZERO);
```

`box` 参数依次为 ID、中心、半尺寸和质量。骨架构建器输入与 `RevivalPose` 一样，采用脚底原点、Y 向上和格单位。

关节枢轴默认使用部件中心；可传入第五个参数：`box(id, centre, halfSize, mass, pivot)`，枢轴也使用身体局部坐标。

要让自动死亡生成也使用它，在通用初始化阶段注册：

```java
CorpseSkeletons.register(MyEntities.MONSTER.get(), source -> skeleton);
```

提供器可以根据来源实体返回不同骨架。注册结果优先于客户端发现结果，服务端无需等待客户端先看见生物。同一实体类型只接受一个提供器，注册返回的句柄可以撤销。

### 其他模型格式

客户端实现现有 `CorpseModelAdapter`，在客户端初始化、连接世界前注册：

```java
CorpseModelAdapters.register(
    new ResourceLocation(MOD_ID, "custom_model"),
    1000,
    new MyModelAdapter());
```

使用的类位于 `com.pyrostella.corpselab.client.model`。上面的 `MOD_ID` 是附属模组自己的 ID 常量。

| 实现方法 | 提供的内容 |
|---|---|
| `supports` | 能否处理该实体和渲染器 |
| `modelKey` | 模型结构的缓存标识 |
| `scan` | 模型对应的 `BodyPlan`，以及实例尺寸采样 |
| `bind` | 逐部件绘制与附加层绘制 |

同一骨架的物理部件 ID 必须与渲染绑定、目标姿态的路径一致。物理骨架与绘制是两个职责；只注册骨架并不会凭空获得第三方格式的渲染器。

默认已包含原版和 GeckoLib 适配器。优先级越大越先匹配；GeckoLib 内建优先级为 100。

## 生命周期事件

事件包：`com.pyrostella.corpselab.api.event`。

全部在**服务端主线程、Forge 主事件总线**发布，注册一次即可：

```java
MinecraftForge.EVENT_BUS.addListener(this::onRevived);

private void onRevived(CorpseReviveEvent.Complete event) {
    var corpse = event.getCorpse(); // 统一尸体视图，实体此时已移除
    var living = event.getRevived(); // 已成功入场的原生物
    // 完成任务、给予奖励等
}
```

也可以使用 `@SubscribeEvent`，但要注册到 Forge 总线，而不是 Mod 初始化总线。

### 生成事件

| 事件 | 时机 | 可取消 |
|---|---|---|
| `CorpseCreateEvent.Pre` | 尸体已初始化，但还未入场、移交物品或淘汰旧尸体 | 是 |
| `CorpseCreateEvent.Post` | 尸体入场、玩家物品交接均成功 | 否 |

```java
private void beforeCreate(CorpseCreateEvent.Pre event) {
    if (event.getSource().filter(source -> source.hasCustomName()).isPresent()) {
        event.setCanceled(true); // 示例：不给有名字的生物留尸体
    }
}
```

`getCorpse()` 获取尸体视图，`getSource()` 获取可选来源实体。

- 仅正式创建入口触发。存档加载、重新跟踪不产生生成事件。
- 取消或入场失败时不发 `Post`，不转移背包，不淘汰旧尸体。
- 交接物品中途异常时恢复背包、清空失败尸体里的副本，再移除失败尸体。
- 自动创建在死亡确认后、原版掉落之前执行。死亡被取消不会留下尸体；创建失败则继续原版死亡和掉落。
- `Post` 是操作已提交的通知，不能用取消或抛异常要求回滚。

### 复活事件

```text
Pre → Start → Tick（每 tick 一次）→ Complete 或 Interrupted
```

| 事件 | 用途 |
|---|---|
| `CorpseReviveEvent.Pre` | 开始前检查，可取消，可用 `setOptions(...)` 替换复活选项 |
| `CorpseReviveEvent.Start` | 已开始，可读取总时长 |
| `CorpseReviveEvent.Tick` | 本 tick 已推进并应用姿态，尚未执行最终交接 |
| `CorpseReviveEvent.Complete` | 原生物与全部乘客入场成功，尸体已移除 |
| `CorpseReviveEvent.Interrupted` | 已开始的复活被中止、销毁或交接失败 |

```java
private void beforeRevive(CorpseReviveEvent.Pre event) {
    event.setOptions(event.getOptions().duration(60)); // 三秒
}

private void duringRevive(CorpseReviveEvent.Tick event) {
    int elapsed = event.getElapsedTicks();
    double progress = event.getProgress(); // 时间进度 0～1，不是缓动曲线
    // 需要打断时：Corpses.stopRevival(event.getCorpse().entity());
}
```

只有 `Pre` 可取消，其余为通知。所有复活事件提供 `getAttemptId()`，同一次复活的 ID 随存档保存。

`Interrupted.getReason()` 返回枚举：

| 原因 | 含义 |
|---|---|
| `STOPPED` | 通过 API 主动停止 |
| `REMOVED` | 尸体被销毁 |
| `INVALID_DATA` | 无法还原原生物 |
| `UUID_CONFLICT` | 最终交接时 UUID 被占用 |
| `SPAWN_FAILED` | 原生物或任一乘客被世界拒绝入场 |

- 前置被取消或参数无效，不发 `Start` 和终结事件。
- 一次已开始的复活，只发一次成功或中断。
- 卸载和保存退出只暂停，不中断；续播不重复发 `Pre/Start`。
- `Tick` 中停止、移除或重新开始复活，不会继续完成旧动画。
- 正在提交原生物和乘客的短暂阶段不接受主动停止，避免半交接。
- 任一乘客入场失败，会移除本轮已生成实体、保留尸体与数据，只发中断。

### 抓取事件

```text
Pre → Start → Tick（每 tick 一次）→ Release
```

| 事件 | 用途 |
|---|---|
| `CorpseGrabEvent.Pre` | 已选中候选肢体，尚未建立关系；可取消 |
| `CorpseGrabEvent.Start` | 抓取已成功 |
| `CorpseGrabEvent.Tick` | 本 tick 施力之前，可修改目标点、拉力 |
| `CorpseGrabEvent.Release` | 关系已清除，可按原因清理附属状态 |

```java
private void duringGrab(CorpseGrabEvent.Tick event) {
    event.setStrength(event.getStrength() * 0.5); // 本 tick 拉力减半
    // setTarget(Vec3) 可以调整牵引目标，仍受断开距离限制
}
```

所有抓取事件提供 `getPlayerId()` 和 `getPartIndex()`。`getPlayer()` 返回可选玩家，离线时可能为空。`Pre.getGrabber()` 是发起抓取的玩家。

`Release.getReason()` 返回枚举：

| 原因 | 含义 |
|---|---|
| `RELEASED` | 主动放手或调用释放 API |
| `DISTANCE` | 超过断开距离 |
| `PLAYER_UNAVAILABLE` | 玩家离线、死亡或成为旁观者 |
| `DISABLED` | 抓取配置关闭 |
| `INVALID_PART` | 被抓部件无效 |
| `REVIVAL` | 尸体开始复活，先释放抓取 |
| `REMOVED` | 尸体被销毁 |
| `UNLOADED` | 尸体卸载或离开维度 |

只有 `Pre` 可取消。`Tick` 中松手使用 `Corpses.releaseGrab(...)`，重复释放不重复通知。

### 嵌套调用

遵循 Forge 优先级和取消传播规则。回调里调用另一项操作，可能立即触发嵌套事件，例如 `Tick` 中停止会立即触发 `Interrupted`。不要假设其他监听器此时已经全部执行。

事件监听异常按 Forge 常规机制传播；正常否决应调用 `setCanceled(true)`。
