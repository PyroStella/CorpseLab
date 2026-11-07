# CorpseLab 本次重构宪法与执行大纲

制定日期：2026-11-07。状态：A–E 全部完成。16 项单元测试、56 项服务端场景、发布包检查及有/无 GeckoLib 客户端验证全部通过。

本文件由本次用户请求授权新建，是本轮代码实施、续接上下文和验收的唯一任务大纲。它不改写现有 README.md、API用法.md、ai开发规范.md。遇到本文件与用户后续明确指令冲突时以用户指令为准，并先更新本文件的决定再实施。遇到证据否定某项方案时，记录证据、替代方案和验收影响，不凭上下文猜测执行。

## 1. 目标与执行边界

目标是在保留玩家实际体验的前提下，使代码更简单、职责更清晰、扩展入口更易使用，并消除有证据的重复计算和分配。不维持旧代码 API、旧网络协议或历史存档格式的兼容壳；当前版本正常保存再读取、复活动画续播仍是必需功能。

“保留体验”由第 4 节逐条定义，不能仅以编译通过替代。错误输入拒绝、权限收回后禁止写配置、创建失败保留原版死亡显示属于修复现有契约，不是新增玩法。

本轮覆盖全部系统的分析，但修改限于第 7 节列出的可验证结构改进。不以修改文件数量、减少行数或套用设计模式数量为目标。不新增搜刮规则、断肢规则、玩家复活、附属数据存储框架或模型拓扑热更新协议。

根规范约束：现有文档只读；中文注释；模组 ID 引用 CorpseLab.MOD_ID；不进行 Git 操作；保留开始时已有的本地改动；修改实现后实际启动客户端，若出现加入世界日志则保留用户测试会话。每次编辑前核对文件修改时间，变化后重新读取。不得关闭已有用户游戏或更改其存档。

## 2. 当前系统职责地图

| 系统 | 当前位置 | 面向外部实现的需求 | 本轮方向 |
| --- | --- | --- | --- |
| 启动与注册 | CorpseLab、internal/registry | Forge 注册配置、实体、物品、网络 | 保持装配入口，注册句柄公开独立类型 |
| 公共门面与查询 | api/Corpses、CorpseView | 附属一两行完成创建、复活、抓取和查询 | 门面仅委托；内部操作与视图分离 |
| 生命周期 | api/event、internal/corpse | 可否决创建/抓取/复活，观察已提交动作 | 保留现有事件，补统一退出通知与创建守卫 |
| 玩家尸体 | CorpseEntity、SettlingBody、RagdollEntity、CorpseMenu | 背包保存、搜刮、穿戴显示、人形物理 | 批量搬运合并同步，保留独立物理配置 |
| 生物尸体与复活 | PlanCorpseEntity、CorpseRevival | 通用骨架物理、来源恢复、复活动画 | 实体委托复活控制器；动画计算独立 |
| 骨架与姿态 | api/model、internal/skeleton | 服务端骨架提供、通用几何描述、物理驱动 | 不可变数据、领域校验、稳定标识、复用列表 |
| 物理内核 | physics | 刚体、接触与关节求解 | 保持独立于 Minecraft，不改数值或求解算法 |
| 环境桥与扩展 | CorpseLimbEntity/Bridge、api/environment | 原版/Forge 流体、爆炸、方块效果、附属行为 | 保留代理实体；小型公开上下文；按注册版本刷新处理器 |
| 模型发现与渲染 | client/model、client/render、compat/geckolib | 原版、GeckoLib 和第三方模型逐块绘制 | 公开只读渲染能力，保留共享模型状态恢复 |
| 网络与缓存 | internal/network、client/sync、PlanRegistry | 客户端发现骨架、服务器权威模拟、客户端插值 | 明确方向、单次调度、清理作用域、创建确认 |
| 设置和菜单 | internal/config、internal/menu/Config*、command | 权限控制、客户端设置、暂存确认菜单 | 提交复核权限，缓存静态元数据 |
| 验证 | src/test、src/gameTest、Gradle | 纯物理验证、世界生命周期、客户端回归 | 复用现有套件，补跨模块语义测试 |

## 3. 数据流与信任边界

### 3.1 死亡与创建

死亡已确认且未被取消 → mixin → 玩家/生物死亡处理器 → Corpses.create → 内部创建事务 → 初始化尸体 → Create.Pre → 世界接受实体 → 玩家背包交接（如需要） → 标记创建提交 → Create.Post → 数量淘汰（生物）。

来源快照包含完整原 NBT，外观快照用于客户端探针。创建失败不转移玩家物品、不淘汰旧尸体，继续原版死亡/掉落。创建中只允许查询/取消，公共修改入口拒绝尚未提交的尸体。加载实体不重发 Create 事件。

客户端隐藏生物原版死亡模型必须有服务端成功生成且本客户端已有可绘制尸体的关联依据，不能仅凭“本地曾上报这个类型”。骨架同步携带可选来源 UUID；不携带来源时不推测匹配。关联记录随 level/连接清理。

### 3.2 模型与骨架

客户端模型适配器 → 类型扫描及实例尺寸 → 网络校验 → PlanRegistry 类型/实例缓存。自动创建选择顺序固定为：已注册服务端提供器 > 当前实例上报 > 类型首次上报。

同一个服务端会话内类型基础骨架首次有效上报生效；本轮不承诺模型拓扑在同会话热替换。资源重载可重新构造绘制绑定，不能绕过服务端骨架身份规则。服务端停止清发现缓存，不清模组注册的提供器。

网络客户端上报不等于权威：检查方向、sender、有效类型、实例存在、所属世界和已有距离限制。不得增加任意固定部件数、尺寸倍率、配置项数量或 NBT 截断上限。

### 3.3 物理与交互

原版/Forge 环境作用 → 独立肢体代理实体 → 环境行为实例 → CorpseLimbBridge 将速度/环境参数带回 → 服务端物理 → 姿态快照 → 客户端 PoseBuffer → 渲染/相机。

抓取请求 → 服务端视线选肢体 → Grab.Pre → 建立唯一关系 → Grab.Start → 每 tick Grab.Tick → 速度牵引 → Release。玩家占用索引仅替代当前同一维度扫描，不能改变可及范围或抢占规则。

### 3.4 复活

轻量入口校验与准备 → Revive.Pre 可替换选项 → 再校验回调后的状态 → 采样目标一次 → 释放抓取 → Start → Tick* → 最终恢复实体树和跨维度 UUID 检查 → 全部入场并再次核对 → 清除尸体 → Complete。任何已开始尝试只能出现一次 Complete 或 Interrupted。

卸载保存动画，加载继续，不重新发布 Pre/Start；销毁中断。Tick 回调停止/删除/重新开始之后，不能继续旧尝试。最终入场事务期间拒绝主动停止。乘客中途失败须回滚本轮已加入成员、保留尸体与来源数据。

## 4. 玩家体验不变量

1. 玩家固定六部件、受限人形关节；生物默认 FREE，ARTICULATED 保留既有断肢行为。禁止为了统一结构强行共用同一套物理参数。
2. 玩家尸体 54 格箱子界面；0–35 背包、36–39 盔甲、40 副手。Shift 取回优先原槽，冲突尝试可用背包，放不下的物品留尸体。普通单槽取装备立即更新显示。
3. keepInventory 开启不搬物品，仍能留下空尸体；everHadItems 保留“从未存物品”与“搜刮后为空”的区别。交接失败恢复背包并清空失败尸体副本。
4. 玩家寿命 0 无限；到期先掉落剩余物品。玩家寿命边界 age >= lifetime，生物边界 age > maxAgeTicks，禁止顺手统一导致行为变化。
5. 玩家静止冻结、每 20 tick 检查支撑，抓取和环境可唤醒。生物远处暂停物理/姿态发送，年龄继续增长；复活时年龄暂停。
6. 新玩家跟踪静止/远处尸体仍须补发完整骨架、外观和当前姿态。包内锚点与刚体同一服务端 tick；客户端 tick 边界提升插值快照的时机保留。
7. 普通玩家攻击和投射物不推动尸体；玩家引起的爆炸仍属环境。尸体不抢战斗选取，搜刮射线保留距离、方块和活体遮挡。
8. 默认复活 40 tick。基准与部件世界 AABB 最短距离 <= 2 格则归位，否则在目标处长回。保持缓动、缩放及首次长回隐藏旧断肢语义，不改为按关节连通性分类。
9. 恢复原实体 UUID、模组附加数据和乘客关系，绝不覆盖活实体。目标姿态只采样一次并保存；停止后保留当前姿态和来源数据。
10. 生物 96 格区域内最老优先淘汰，只在新尸体创建提交后执行。取消创建或入场失败不影响旧尸体。
11. 同类不同外观尸体使用独立探针；共享模型采样和附加层绘制均需 finally 恢复模型状态。缺失 GeckoLib 时正常启动。
12. 配置菜单确认前不写盘，取消丢弃暂存。普通玩家只改自身客户端项，管理员才可改服务端项；提交时再次检查权限。

## 5. 公共 API 目标契约

- 保留 Corpses 的简单调用方式和 boolean/Optional 结果。true 表示开始成功，最终结果由事件确认。诊断是当前条件查询，不记录上次失败，也不替代直接操作。
- 修改仅在服务端主线程执行；查询视图跟随实体，部件记录是不可修改的脱离求解器快照；客户端未同步数据保持 UNKNOWN/空值。
- 注册入口返回 api.Registration：AutoCloseable，无受检异常，重复关闭无副作用，旧句柄不能删后来同 ID 的注册。
- 客户端 Binding 与 layer adapter 依赖 api.client.CorpseRenderContext；提供标准 Entity、骨架、外观和已采样部件位置/旋转/缩放等必要能力，不暴露 PlanCorpseEntity 或 PoseBuffer。
- 环境 Context 的 limb 使用标准 Entity，并直接提供所属 CorpseView；附属无需引用 CorpseLimbEntity 或遍历内部链。Session 调度属于内部实现。
- GeckoRevivalPoses 公开位置采用 api.client，与现有用法文档一致；GeckoLib 类型仅允许客户端工具使用，不进入通用服务端签名。
- BodyPlan 保存现有模型坐标：模型像素 /16，原版绘制原点对应模型 y=1.501；与公开 builder/RevivalPose 的脚底原点、Y 向上坐标存在明确转换 (-x, 1.501-y, z)。不改变实际几何。路径是渲染、复活、基准选取共同使用的稳定 ID，必须非空白且唯一。
- BodyPlan 列表防御复制。领域有效性归骨架数据，网络 codec 仅负责编码及在分配前检查载荷数量。允许内部空骨架占位，公开创建必须非空。
- 保留创建、抓取、复活现有事件顺序、取消语义与 attempt ID。监听器异常按 Forge 机制传播；正常否决用 setCanceled，不将任意异常吞为 false。
- 增加 CorpseRemovalEvent：实体退出且抓取/复活清理完成后通知，区分 EXPIRED、EMPTY、LIMIT、REVIVED、DISCARDED 与 UNLOADED。UNLOADED 非永久销毁；加载后允许再产生下一次卸载。失败创建不发布该尸体的正式退出事件。
- CorpseView.State.ACTIVE 表示可活动且未处于特殊状态，不承诺本 tick 求解器实际执行；远处暂停不创造新的玩法状态。

## 6. 六原则的具体判定

| 原则 | 本轮可检查的结果 |
| --- | --- |
| 单一职责 | 门面不搬物品/组装视图，实体不编排实体树复活事务，骨架领域不依赖网络校验，注册变化管理不混入每次环境回调 |
| 开闭 | 新模型、骨架、环境行为使用现有注册入口；不向核心加入按第三方实体类型硬编码的分支 |
| 里氏替换 | 玩家/生物统一查询与抓取契约一致；搜刮、复活能力显式区分，不要求玩家尸体假装支持复活 |
| 接口隔离 | 常规附属只依赖 Corpses/CorpseView；模型、环境、生命周期使用独立小接口，不新增万能服务接口 |
| 依赖倒置 | 扩展回调依赖公开能力接口，不依赖具体内部尸体/缓存/求解器类型；不为每个单实现类强建接口 |
| 迪米特法则 | 上下文直接给所需能力，附属无需 corpse.ragdoll().world() 等内部导航 |

内联只用于没有业务含义的单次转发；不以内联事务步骤来减少调用次数。JVM 可内联小方法，模块清晰优先于没有测量依据的微优化。

## 7. 全部实施计划与顺序

前置分析已完成：系统地图、数据流、两类用户需求、扩展推演、风险与验证矩阵均已确定。至本文件建立之前未修改实现代码。

### A. 公共扩展边界与骨架数据

1. 新建公开 Registration，所有注册器使用它，不保留旧嵌套句柄。
2. 引入 CorpseRenderContext 与内部适配实现，更新原版/GeckoLib、附加层及客户端测试，不让扩展签名依赖内部实体。
3. 环境 Context 去内部实体类型；调度 Session 移至 internal/environment。保留每肢体实例与优先级规则。
4. BodyPlan 不可变快照、坐标注释与稳定 ID、领域验证及最大部件索引统一；所有原有入口改用领域验证，不保留 codec 的冗余验证实现。
5. GeckoRevivalPoses 移到 api.client，全部引用同步，不保留旧路径兼容类。

### B. 生命周期内部职责与热路径

1. PlanCorpseEntity 的开始/推进/中断/完成/存档复活状态委托 CorpseRevivalController；CorpseRevival 只负责动画采样和应用。重入与事务语义不变。
2. 玩家容器批量搬运/加载/掉落期间仅在结束同步装备；普通单槽更新照常。合并重复取回槽位逻辑并删除单纯 Supplier 包装。
3. 抓取占用改为同一 ServerLevel 下 UUID 索引，使用弱引用或完整生命周期清理避免持有旧世界；旧释放不能删除新关系。
4. 复用 PlanRagdoll 的不可修改刚体列表；rigidBodies 不重复复制；最大部件查询使用领域索引，删除重复包围盒覆盖。
5. 删除明确仅供旧格式存在的存档回退分支；当前正常存档仍完整支持。损坏骨架不得留下永久无物理空壳。

### C. 网络和缓存生命周期

1. 所有消息声明收发方向；只在一个位置调度主线程；保留原有载荷完整性和实例距离校验，补 sender/有效注册类型检查。
2. 服务器停止清理发现骨架缓存。客户端按连接、level、资源重载三个生命周期清理相应扫描/实例/绘制缓存。
3. 删除无生产调用的类型级 PlanModelCache 路径与未使用查找转发。保留每尸体外观绑定和失败 20 tick 重试。
4. 骨架同步增加可选来源 UUID，客户端仅在对应尸体存在且有可绘制姿态后隐藏来源死亡模型；协议版本递增，不保留旧解码。

### D. 门面、创建与退出事件、配置

1. Corpses 成为薄门面，内部 CorpseOperations 负责操作，CorpseViews 负责统一查询，玩家创建/物品回滚独立成创建服务。
2. 为创建中状态设置守卫，创建提交前禁止公共复活/抓取；失败无正式退出通知。创建服务统一提交点，Post 为已提交通知；Post 发布后才淘汰旧尸体，避免旧尸体退出回调在新尸体 Post 前启动新动作。
3. 新增统一 CorpseRemovalEvent 和内部原因标记；覆盖两类尸体、超时、搜刮空、区域淘汰、复活、直接 discard 和卸载，确保通知不重复。
4. LimbEffects 在注册快照变化时重建有序处理器，稳态回调不重复 retainAll/compute；不改优先级和每肢体状态。
5. 配置静态元数据按路径/分区缓存，不每次菜单重建重复分组；提交逐项复查权限，移除未使用遍历参数。

### E. 集成验证与交付

按第 9 节验证；检查公开签名、旧路径残留、硬编码 ID 和失败行为。记录实测结果及无法完成的环境项。启动有/无 GeckoLib 客户端；不关闭用户测试会话。最终交付代码、此文件中的执行记录与验证证据，不修改用户维护的旧文档。

### 已评估但不采用的候选

- 不合并玩家与通用物理或两套姿态协议：参数和载荷不同，强行合并收益不足且扩大插值风险。
- 不重写 XPBD/碰撞算法、不复用求解器 scratch：缺少性能测量，别名错误风险大。
- 不改变附加层共享模型恢复流程或视锥剔除范围：当前表现对静态旋转、散落肢体和第三方渲染敏感。
- 不引入完整 ECS、依赖注入容器或通用工作流框架：现有规模不需要。
- 不将所有异步/客户端误用改为抛异常：保留 boolean/Optional 的简易调用契约。

## 8. 以附属开发者为起点的需求推演

| 场景 | 外部最小接入 | 满足条件 |
| --- | --- | --- |
| 复活法杖 | Corpses.revive + Start/Complete/Interrupted | 开始与完成含义明确；attempt ID 关联，不重复奖励/扣费 |
| 领地禁止搬运 | 取消 Grab.Pre | 不建立关系、不发 Start，不影响抓别处 |
| 牵引法术 | Grab.Tick 设置目标/拉力 | 当 tick 生效，超距释放，回调内释放后不继续旧施力 |
| 独立服务器 Boss | 注册 CorpseSkeletons provider | 不等待客户端；注册骨架优先；按来源状态生成 |
| 新模型引擎 | 注册 model adapter 与 layer adapter | 不导入 internal 类型；路径对齐；确定优先级 |
| 魔法液体/失重 | 注册环境 factory | 每肢体状态独立；高优先级最后覆盖；撤销对既有肢体生效 |
| 墓地/任务索引 | Create.Post 建记录，RemovalEvent 清理 | 创建失败无记录；卸载区别永久删除；原因可判定 |
| 自定义复活姿态 | RevivalOptions.base/pose/target | 未知路径拒绝；一次采样；保存续播；模型状态恢复 |
| 只读 HUD | Corpses.find + CorpseView | 玩家和生物同入口；能力明确；客户端未知不伪造 |
| 初始化注册后撤销 | try-with-resources Registration | 可撤销、可重复关闭、旧句柄不误删新注册 |

API 示例和相关测试必须可编译。客户端扩展与通用服务器 API 分开使用；不把 GeckoLib 客户端类放到服务端入口。

## 9. 验收与停止条件

实施前基线：gradlew test gameTestClasses --offline 已通过，12 个现有单元测试通过，所有已有游戏测试编译通过。编译存在 Forge 上下文及 ResourceLocation 的既有弃用警告。

必要自动验证：

- test、gameTestClasses、check/verifyProductionJar：物理稳定性、注册器快照、正式包无测试类。
- runGameTestServer：现有创建/API/抓取/复活/来源/外观/环境/网络/路径用例；新增创建中修改拒绝、退出事件、权限提交、索引释放、无效路径/不可变性、缓存清理用例。
- 客户端内建回归（现有 corpseRenderTest 等入口）：原版/GeckoLib 显示、附加层、目标姿态采样、无 GeckoLib 启动、死亡创建失败显示回退。
- 256 部件、合法任意缩放、超过 128 设置项、大型 NBT、不分配超出载荷声明的集合、数值/拓扑/路径拒绝全部保持。
- 存档继续复活、最终乘客入场失败回滚、Tick 重入中止、UUID 活体冲突、抓取抢占和卸载释放为高风险验证项。

无自动像素或真实玩家行为证据时，不宣称已证明全部视觉/手感完全等价；明确记录自动验证覆盖与仍需人工观察的边界。客户端启动成功必须有加载到标题/渲染运行的日志证据，而不是只证明 Gradle 任务进程启动。

停止条件：计划 A–E 的实现全部完成，必要检查通过，客户端已可启动，无未解释的失败；若环境阻止某项，记录实际命令/错误与未验证范围，不伪报成功。

## 10. 执行记录

- 2026-11-07：先完成三个并行只读审计及主代理配置/构建分析，建立此大纲后才修改实现。实施前测试基线通过。
- A–D 主要实现完成：公共接口、领域校验、复活控制器、统一创建/查询、退出事件、抓取索引、环境快照、配置元数据与权限、网络方向和缓存寿命已接通。
- 首轮运行：16 单元测试通过，51 服务端场景有 1 个夹具失败（旧不完整 NBT）；已改成当前真实保存/读取。新增回调重入和跨维度场景后，55/55 服务端测试及 check/发布包检查通过。
- 集成决定：新增统一退出通知后，生物 Create.Post 调整到区域淘汰前发布，避免淘汰旧尸体产生的嵌套回调早于新尸体提交通知；淘汰范围、数量和最老优先规则不变。复活 Pre 前后完整重新校验继续保留，因为监听器可 load 新来源数据；不为省一次构造增加来源版本状态或跨 tick 缓存。

- 复核决定：退出清理统一接入 Entity.setRemoved 尾部的尸体专用 Mixin，覆盖跨维度和正常隐藏区段后区块卸载的 final setRemoved 路径；移除原有 remove/onRemovedFromWorld 重复覆盖。通知在交互清理后且每次退出只发布一次。onRemovedFromWorld 在区段先隐藏时 reason 仍为空，不能提前将暂时不可见判为退出。增加 CHANGED_DIMENSION 与隐藏后卸载回归。


## 11. 当前对外接入速查

普通附属仍只需 `api.Corpses` 和 `api.CorpseView`。直接调用 `Corpses.revive(entity, 60)`，返回 true 仅表示已开始；不必先调用 canRevive。最终任务奖励或成功结算监听 `CorpseReviveEvent.Complete`，失败清理监听 Interrupted，同次尝试以 getAttemptId 关联。

创建、复活、抓取的取消仍只发生于各自 Pre。Create.Pre 可查询，但公共修改接口拒绝未提交尸体；此阶段的 revivalProblem 为 INVALID_ENTITY。Create.Post 发布后可立即操作，此时玩家物品交接已完成。Post 监听器可删除尸体，返回的视图仍反映其真实移除状态；通知不会回滚已提交创建。

统一退出事件位于 `api.event.CorpseRemovalEvent`：getCause() 返回 EXPIRED、EMPTY、LIMIT、REVIVED、DISCARDED、UNLOADED，getRemovalReason() 返回原版原因，isPermanent() 区分永久退出和卸载。REVIVED 退出通知先于同次复活 Complete，原生物已经成功入场；附属若需要奖励对象使用 Complete.getRevived()。卸载不发布 Interrupted，保存的尝试加载后继续，不重新发 Start。

注册句柄统一为 `api.Registration`。骨架注册使用 `CorpseSkeletons.register(entityType, source -> skeleton)`；模型注册使用 `api.client.CorpseModelAdapters.register(id, priority, adapter)`；层注册使用 `api.client.CorpseLayers.register(...)`；行为注册使用 `api.environment.LimbEffects.register(id, priority, factory)`。句柄可 close 撤销，旧句柄不能删新注册。

模型 Binding 的 prepare/renderLayers 接收 `api.client.CorpseRenderContext`。使用 plan()/partPosition()/partRotation(index, destination)/partScale()，不接触内部 PoseBuffer 或尸体实体。appearance() 是副本，entity() 为标准 Minecraft Entity。模型扫描路径、骨架路径和复活目标路径必须一致。

行为实现 `LimbEnvironmentEffect`，Context.limb() 为标准 Entity，Context.corpse() 为统一 CorpseView。Entity 的 deltaMovement 单位仍为格/tick；Corpses.create 初速度和物理求解器使用格/秒。tick 中 setPhysics 的参数在所有行为完成后一次提交；回调期间注册/撤销不改变正在执行的快照，下次调度生效。

内建动画工具为 `api.client.VanillaRevivalPoses` 和 `api.client.GeckoRevivalPoses`，只在客户端调用。公共服务端通过 RevivalPose/RevivalTarget 接收数据。原模型格式以外的引擎分别提供服务端骨架与客户端适配器，无需继承内部实体。

- 最终服务端验证：56/56 全部通过，含真实隐藏区段卸载与直接跨维度。临时测试配置原先写盘触发异步文件监视器污染，现统一使用 TestConfigs 内存覆盖并在每批次重置服务端默认值；不更改生产配置持久化。无 GeckoLib 客户端启动自检（已安装=false）与 Java 姿态采样通过，未进入世界后结束该标题界面测试进程。
- 客户端第一轮：GeckoLib 8 种内建生物及缩放/物理全部通过，真实复活场景观察 180 帧并成功交接。两个新增客户端夹具已修正：位置比较容许有符号零/浮点等价，临时负 ID 在加入 ClientLevel 前显式设置实体 ID。生产渲染逻辑未为夹具作改动。


## 12. 最终验收记录

全部计划 A–E 已完成，以下为本次实际运行结果，不是预期结果。

- `gradlew.bat check runGameTestServer --offline`：BUILD SUCCESSFUL；16 个单元测试（骨架 4、注册器 5、物理 7）无失败；56/56 必需 GameTest 全部通过。包含物品异常回滚、UUID 冲突、乘客失败、复活重入/续播、区域淘汰重入、维度直调和真实隐藏 section 后卸载、注册撤销、256 部件/大 NBT/长路径、配置提交权限与网络生命周期。
- 无 GeckoLib：独立目录 `build/refactor-client-no-gecko` 实际启动，日志“GeckoLib 可选兼容启动自检通过，已安装=false”及 `REVIVAL_JAVA_FIRST_FRAME_OK`，图集初始化完成。未进入任何世界后仅结束此次标题界面烟雾测试进程；其 Gradle 退出码来自主动结束进程，不是启动异常。
- 有 GeckoLib：最终目录 `build/refactor-client-gecko-recheck` 的独立 `validation` 世界实际进入，成功标记包括 `REVIVAL_GECKO_FIRST_FRAME_OK`、`VANILLA_REVIVAL_SCALE_RENDER_OK`、原版多层材质自检、`GECKO_ENTITY_RENDER_ALL_OK count=8`、`CLIENT_CORPSE_CACHE_LIFECYCLE_OK`、`REVIVAL_SCENE_CLIENT_OK frames=158`。最后一轮日志没有 ERROR 或 FAILED 标记。
- 实际覆盖原版村民地区/职业/等级、羊毛/剪毛、护甲、发光眼睛、独立外观、共享模型恢复、GeckoLib 8 种内建实体、零/中途长回缩放、资源重载、适配器变化、世界缓存清理、创建失败显示回退、服务端驱动到客户端的真实复活交接。
- 正式产物 `build/libs/corpselab-0.1.0.jar`（412828 字节）包含新 API、控制器和移除 Mixin；发布包检查与直接 ZIP 内容核对均无测试泄漏，旧 Gecko 工具路径不存在。
- 已进入世界的两次客户端测试会话保持运行。用户原有 `run/saves` 未使用或修改；独立世界只复制本轮生成的 GameTest 世界元数据后重新生成区块。现有 README.md、API用法.md、ai开发规范.md 未修改，无 Git 写入操作。

可复查证据：`build/test-results/test/TEST-*.xml`、`build/refactor-gametest.log`、`run-gametest/logs/latest.log`、`build/refactor-client-no-gecko/logs/latest.log`、`build/refactor-client-gecko-recheck/logs/latest.log`。运行中的客户端日志后续可能追加内容；以上成功标记来自本次验收。

性能结论限于可检查的工作量变化：抓取占用查询由两次全维度遍历改为索引查找；批量物品交接只在结束同步六件装备；固定刚体列表和最大部件索引复用；配置元数据只构建一次；环境注册稳态不重建处理器集合；网络仅调度一次主线程。本轮未进行 FPS/CPU 基准，不给出未经测量的百分比提速。

自动验证覆盖本节列出的模型与场景，没有宣称对所有第三方模型完成逐像素或人工手感等价检验。当前会话不支持同类型骨架拓扑热替换，这一约束未改变；新增格式通过服务端骨架提供器及客户端模型适配器接入。
