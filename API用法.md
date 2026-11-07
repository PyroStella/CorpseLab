# CorpseLab API 用法

附属模组的统一使用文档，对应当前实现。示例中的 `MyMod`、`MyEntities`、`MyModelAdapter` 等由附属模组提供。

普通尸体操作使用 `api.Corpses`，不需要导入 `internal.*`、内部尸体实体或物理求解器。

- [基础用法](#基础用法)
- [模型、骨架与复活目标](#模型骨架与复活目标)
- [注册与撤销扩展](#注册与撤销扩展)
- [环境与行为扩展](#环境与行为扩展)
- [生命周期事件](#生命周期事件)

## 基础用法

### 准备

```java
import com.pyrostella.corpselab.api.Corpses;
import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.RevivalProblem;
```

下文 `entity` 是已知的尸体或其肢体实体，`source` 是来源活体（`LivingEntity`），`player` 是操作玩家。

修改操作在**服务端主线程**调用。普通服务端事件回调中可直接调用；客户端和异步任务不能直接修改尸体。正常条件不满足时返回 `false` 或空 `Optional`；扩展实现错误、无效的构建器参数和事件监听异常仍按 Java／Forge 机制传播。

公开入口按运行端划分：

| 包或类型 | 使用范围 |
|---|---|
| `api.Corpses`、`CorpseView`、`RevivalOptions`、`RevivalPose` | 通用类型，修改操作仅服务端主线程 |
| `api.CorpseSkeletons`、`api.model.BodyPlan` | 服务端骨架提供与通用骨架数据 |
| `api.event.*`、`api.environment.*` | 服务端生命周期监听与环境行为 |
| `api.client.*` | 客户端模型、附加层与动画采样 |
| `api.Registration` | 扩展注册的撤销句柄 |

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
- 抓取仍受距离、视线选取和配置限制，不能抢走已被抓住的尸体；同一玩家在同一维度只能抓取一具尸体。
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

`CorpseCreateEvent.Pre` 中的尸体尚未提交，此时生物尸体的诊断为 `INVALID_ENTITY`，不能通过公共 API 开始复活或抓取。

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

视图跟随实体变化；精确玩法查询应在服务端线程进行。当前部件、抓取和复活进度只提供服务端数据，客户端分别返回空集合或空 `Optional`，未同步的模拟状态为 `UNKNOWN`。`ACTIVE` 表示未冻结且未处于抓取、复活等特殊状态，不保证该 tick 实际推进了物理；远处暂停的生物尸体也可能为 `ACTIVE`。

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

创建提交成功返回尸体视图，失败返回空。创建不会杀死来源实体，死亡时模组已自动创建尸体，不要在死亡监听中再创建一遍。

`Create.Post` 回调可以立即操作或移除尸体；回调执行完后返回的视图会反映最新状态。因此返回非空表示创建曾经成功提交，不保证回调后实体仍未移除。

**创建玩家尸体会转移背包物品**，`keepInventory` 开启时不转移。生成失败不会转移物品。

生物需要已有骨架数据，否则创建失败。需要自定义初速度时：

```java
Corpses.create(source, new net.minecraft.world.phys.Vec3(0, 3, 0));
```

速度单位为格/秒。已有 `BodyPlan plan` 的模型适配方还可以显式指定骨架：

```java
Corpses.create(source, plan, net.minecraft.world.phys.Vec3.ZERO);
```

显式骨架仅供生物使用。玩家使用内置骨架，应使用前两种重载；调用三参数重载时 `plan` 必须为 `null`。

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

提供器可以根据来源实体返回不同骨架。创建时的选择顺序为：**已注册服务端提供器 → 当前实体实例上报 → 类型首次上报**。服务端提供器不需要等待客户端先看见生物；同一实体类型只接受一个提供器，注册返回的 `Registration` 可以撤销。提供器返回 `null`、空骨架或无效数据会抛出 `IllegalArgumentException`，不会回退到客户端骨架。

### 骨架数据与坐标约定

建议用 `CorpseSkeletons.builder()` 构造骨架。部件 ID 必须非空白且在骨架内唯一，列表下标与 `Box.index()` 一致；关节不能自连、成环或为同一子部件指定多个父部件。质量必须为正，半尺寸非负，数值必须有限且可用 float 表示。

`BodyPlan` 构造时复制部件和关节列表，调用方随后修改原集合不会改变骨架。`plan.isValid()` 校验几何、路径及拓扑；内部空骨架可以通过该校验，但正式创建仍要求 `plan.boxCount() > 0`。`plan.largestPartIndex()` 返回最大体积部件下标，体积相同时选择首个，空骨架返回 `-1`。

需要直接构造 `BodyPlan.Box` 的适配器，应区分两种坐标：

| 数据 | 原点与方向 |
|---|---|
| 骨架构建器的中心／枢轴、`RevivalPose.Bone.position()` | 身体局部坐标，脚底为原点，Y 向上，单位为格 |
| `BodyPlan.Box` 中的中心／枢轴 | 渲染模型坐标，单位为模型像素 / 16 |
| `CorpseView.Part.position()`、`CorpseRenderContext.partPosition()` | 世界坐标，单位为格 |

身体局部坐标 `(x, y, z)` 转为 `BodyPlan.Box` 存储坐标时使用 `(-x, 1.501 - y, z)`，逆转换也是同一公式。构建器已经执行该转换，不要重复转换。半尺寸和质量不执行坐标翻转。

`Box.path()` 是渲染绑定、复活目标和基准选择共用的稳定 ID，不是仅供调试的名称。

### 其他模型格式

客户端实现现有 `CorpseModelAdapter`，在客户端初始化、连接世界前注册：

```java
CorpseModelAdapters.register(
    new ResourceLocation(MOD_ID, "custom_model"),
    1000,
    new MyModelAdapter());
```

公开接口和注册入口位于 `com.pyrostella.corpselab.api.client`：

```java
import com.pyrostella.corpselab.api.client.CorpseModelAdapter;
import com.pyrostella.corpselab.api.client.CorpseModelAdapters;
import com.pyrostella.corpselab.api.client.CorpseRenderContext;
```

上面的 `MOD_ID` 是附属模组自己的 ID 常量；不需要使用内部模型扫描或尸体实体类型。

| 实现方法 | 提供的内容 |
|---|---|
| `supports` | 能否处理该实体和渲染器 |
| `modelKey` | 模型结构的缓存标识 |
| `scan` | 模型对应的 `BodyPlan`，以及实例尺寸采样 |
| `bind` | 逐部件绘制与附加层绘制 |

同一骨架的物理部件 ID 必须与渲染绑定、目标姿态的路径一致。物理骨架与绘制是两个职责；只注册骨架并不会凭空获得第三方格式的渲染器。

默认包含原版适配器，以及安装 GeckoLib 时启用的 GeckoLib 适配器。优先级越大越先匹配；原版内建优先级为 0，GeckoLib 为 100。

`modelKey(...)` 返回模型结构的缓存标识，按对象身份比较；结构不变时复用同一标识。`Scan.capture(...)` 必须保留扫描结果的部件路径、顺序和关节，只改变实例尺寸。`bind(...)` 暂时无法构造绑定时可返回 `null`，现有尸体会按 20 tick 间隔重试。

客户端上报不等于服务端确认。同一服务端会话中，每种类型采用首次有效的基础骨架；本版本不支持同类型骨架拓扑在会话中热替换。资源重载会清理扫描和绘制绑定，但不会替换服务端已接收的类型骨架。发现缓存在服务端停止时清理，模组注册的骨架提供器不随之撤销。

### 绘制绑定与只读上下文

`CorpseModelAdapter.Binding` 的两个回调都使用公开的 `CorpseRenderContext`：

| 回调 | 职责 |
|---|---|
| `prepare(CorpseRenderContext, MultiBufferSource, float)` | 每次绘制前准备材质／缓冲，返回 `CorpseModelAdapter.PartRenderer` |
| `renderLayers(CorpseRenderContext, PoseStack, MultiBufferSource, int, float)` | 绘制附加层，默认不执行操作 |
| `PartRenderer.render(BodyPlan.Box, PoseStack, int)` | 绘制指定部件的几何 |

进入 `PartRenderer.render` 时，栈已包含该部件的世界位置、旋转和复活缩放；不要重复应用它们。适配器负责模型局部变换和部件几何，避免连带绘制其它部件。缩放为零的部件由库跳过。

| 上下文方法 | 数据 |
|---|---|
| `entity()` | 标准 Minecraft `Entity`，可读取位置、年龄和所属世界 |
| `plan()` | 当前不可变骨架 |
| `sourceType()` | 来源实体类型 ID |
| `appearance()` | 外观 NBT 的独立副本 |
| `partPosition(index)` | 本绘制帧已采样的部件世界坐标 |
| `partRotation(index, destination)` | 将旋转写入调用方的 `Quaternionf`，不返回内部可变对象 |
| `partScale(index)` | 已采样的复活缩放，可为零 |

上下文只用于当前绘制回调，不要跨线程或跨帧缓存。不同尸体拥有独立的外观探针；若适配器修改共享模型，须在 `finally` 中恢复状态。

`renderLayers` 的输入栈没有逐部件变换。自行绘制特殊层时，需要按上下文的部件姿态定位；复用原版 ModelPart 附加层可使用下面的注册入口。

### 附加层扩展

客户端使用 `api.client.CorpseLayers`，通过 `register(id, priority, adapter)` 注册。实现 `CorpseLayers.Adapter` 的 `supports(RenderLayer<?, ?>)` 和 `render(CorpseLayers.Context)`。

`Context` 提供 `layer()`、`model()`、`probe()`、`corpse()`、`stack()`、`buffers()`、`light()`、`partialTick()`。其中 `corpse()` 是公开的 `CorpseRenderContext`，不需要转换为内部实体。

在 `render` 回调中可复用默认 ModelPart 绘制：

```java
// context 为 CorpseLayers.Context，extraModel 为附加层实际使用的 EntityModel
context.renderModelParts();
// 需要纳入额外模型时，改为调用以下形式
context.renderModelParts(extraModel);
```

两种形式择一调用，避免重复绘制。非 ModelPart 几何可以直接使用上下文绘制。层适配器按优先级从高到低选择第一个匹配项；没有匹配项时使用内建默认绘制。

## 注册与撤销扩展

四种扩展入口统一返回 `com.pyrostella.corpselab.api.Registration`：

| 入口 | 注册时机 | 重复注册规则 |
|---|---|---|
| `CorpseSkeletons.register(type, provider)` | 通用初始化 | 同一实体类型只接受一个提供器 |
| `CorpseModelAdapters.register(id, priority, adapter)` | 客户端初始化、连接世界前 | 同一模型扩展 ID 不可重复 |
| `CorpseLayers.register(id, priority, adapter)` | 客户端初始化 | 同一层扩展 ID 不可重复 |
| `LimbEffects.register(id, priority, factory)` | 通用初始化，也支持运行时调整 | 同一行为扩展 ID 不可重复 |

需要主动撤销时，保存句柄：

```java
import com.pyrostella.corpselab.api.Registration;

Registration registration = CorpseSkeletons.register(
    MyEntities.MONSTER.get(), source -> skeleton);

// 不再需要该提供器时撤销
registration.close();
```

`close()` 不抛受检异常，重复关闭无副作用。撤销后允许重新注册；旧句柄不能删除后来使用相同 ID／类型的新注册。不需要撤销时可忽略返回值。句柄也支持 try-with-resources，离开代码块会立即撤销，因此长期生效的初始化注册应按所需生命周期保留。

模型和层扩展优先级越高越先匹配，同优先级按扩展 ID 字符串升序匹配。环境行为会执行所有匹配项，顺序见下一节。

## 环境与行为扩展

通用初始化或服务端运行时，通过 `api.environment.LimbEffects` 注册工厂。工厂必须为每个肢体创建独立处理器，不要返回共享的有状态单例。

```java
import com.pyrostella.corpselab.api.Registration;
import com.pyrostella.corpselab.api.environment.LimbEffects;
import com.pyrostella.corpselab.api.environment.LimbEnvironmentEffect;
import net.minecraft.resources.ResourceLocation;

Registration registration = LimbEffects.register(
    new ResourceLocation(MyMod.MOD_ID, "low_gravity"),
    100,
    () -> new LimbEnvironmentEffect() {
        @Override
        public boolean supports(LimbEffects.Context context) {
            return context.corpse().entity().getTags().contains("low_gravity");
        }

        @Override
        public void tick(LimbEffects.Context context) {
            context.setPhysics(0.5, 1.0, 0.85);
        }
    });
```

示例对带有 `low_gravity` 实体标签的尸体减半重力。可按需实现以下回调，其余方法使用默认行为：

| 方法 | 时机 |
|---|---|
| `supports(Context)` | 每次回调前判断该肢体是否适用，默认 true |
| `tick(Context)` | 肢体服务端 tick 的环境处理阶段 |
| `hurt(Context, DamageSource, float)` | 肢体接受有效环境伤害时 |
| `explosion(Context, Explosion)` | Forge 爆炸影响列表包含该肢体时 |

`Context.limb()` 返回标准 `Entity`，可读取位置、流体状态和速度；`Context.corpse()` 直接返回所属 `CorpseView`；`onGround()` 读取求解器的接地状态。无需导入内部肢体实体或导航物理世界。

通过 `context.limb().setDeltaMovement(...)` 调整环境速度时，单位是**格/tick**；`Corpses.create` 的初速度单位是**格/秒**，两者不可混用。环境速度由库带回物理模拟，不应直接移动肢体实体来代替求解。

`setPhysics(gravityScale, frictionScale, supportDamping)` 只在 `tick` 回调中使用：三个参数必须有限，摩擦倍率非负，支撑阻尼在 0～1 范围内。每次环境 tick 先重置为 `(1.0, 1.0, 0.85)`，全部处理器运行完成后统一提交。

环境行为按低优先级到高优先级执行，高优先级可覆盖前面的物理参数；同优先级按扩展 ID 字符串降序执行。内建流体和伤害击退行为优先级均为 0。行为实例可保存该肢体的计数、冷却等状态，但不保证跨肢体代理重建或存档卸载保留。

动态注册或撤销会在既有肢体的下一次调度生效。一次调度开始后使用固定处理器快照，在回调中注册或撤销不会改动当前遍历。

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
| `CorpseCreateEvent.Post` | 尸体入场、玩家物品交接均成功，尚未进行生物区域数量淘汰 | 否 |

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
- `Pre` 中可以查询和取消，公共复活、抓取等修改入口会拒绝尚未提交的尸体；世界入场回调内也不能提前通过公共 API 启动动作。
- `Post` 是操作已提交的通知，不能用取消或抛异常要求回滚。生物区域淘汰在 `Post` 之后执行；若回调已删除新尸体，不继续为这次创建淘汰旧尸体。
- 失败创建不发布正式 `CorpseRemovalEvent`；正常存档加载不重新发布创建事件。

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

### 退出与移除事件

`CorpseRemovalEvent` 在抓取关系、必要的复活中断和肢体代理清理完成后发布，不可取消。同一实体的同一次最终退出只通知一次，重复调用移除不会重复通知。

```java
import com.pyrostella.corpselab.api.event.CorpseRemovalEvent;

private void onCorpseRemoved(CorpseRemovalEvent event) {
    var corpseId = event.getCorpse().entity().getUUID();
    if (event.isPermanent()) {
        // 按 corpseId 删除墓地索引、任务记录等永久关联
    } else {
        // 卸载或跨维度退出，暂停当前世界的关联，保留持久记录
    }
}
```

使用前述 Forge 主总线注册方式注册该监听方法。`getCause()` 返回 `CorpseRemovalEvent.Cause`：

| 原因 | 含义 |
|---|---|
| `EXPIRED` | 达到寿命上限；玩家剩余物品已按原规则掉落 |
| `EMPTY` | 玩家尸体曾装过物品，搜刮清空后按配置移除 |
| `LIMIT` | 生物尸体被区域数量限制淘汰 |
| `REVIVED` | 原生物及乘客成功恢复后，尸体退出 |
| `DISCARDED` | 直接删除或其它永久移除，没有更具体的内建原因 |
| `UNLOADED` | 区块／世界卸载或离开维度，非永久销毁 |

`getRemovalReason()` 返回原版 `Entity.RemovalReason`，可进一步区分 `UNLOADED_TO_CHUNK`、`CHANGED_DIMENSION` 等。`isPermanent()` 在原因不是 `UNLOADED` 时为 true。单纯失去跟踪、区段暂时不可见，不触发退出事件；真正卸载时才通知。

- 正在复活的尸体被永久销毁：先发 `CorpseReviveEvent.Interrupted`，再发退出事件。
- 复活成功：先发原因 `REVIVED` 的退出事件，再发 `CorpseReviveEvent.Complete`。需要恢复后的实体时，在 `Complete.getRevived()` 中取得。
- 卸载或跨维度退出：释放抓取并通知退出，不中断已保存的复活动画；加载后的实体继续原尝试，不重复发 `Pre/Start`。
- 视图对应已退出的实体。重新加载后应通过新实体重新取得视图，不把旧实体引用当作新的运行实例。

### 嵌套调用

遵循 Forge 优先级和取消传播规则。回调里调用另一项操作，可能立即触发嵌套事件，例如 `Tick` 中停止会立即触发 `Interrupted`。不要假设其他监听器此时已经全部执行。

事件监听异常按 Forge 常规机制传播；正常否决应调用 `setCanceled(true)`。
