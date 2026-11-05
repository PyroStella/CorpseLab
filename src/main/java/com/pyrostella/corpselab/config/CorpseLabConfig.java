package com.pyrostella.corpselab.config;

import com.pyrostella.corpselab.plan.PlanConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 玩法与物理数值放在 SERVER spec 上，由服务端把它们下发给每个客户端
 * 表现层相关的放在 CLIENT spec 上
 *
 * <p>这里的默认值只是调参的起点，不是最终值
 */
public final class CorpseLabConfig {

    public static final Server SERVER;
    public static final ForgeConfigSpec SERVER_SPEC;

    public static final Client CLIENT;
    public static final ForgeConfigSpec CLIENT_SPEC;

    static {
        Pair<Server, ForgeConfigSpec> server = new ForgeConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();

        Pair<Client, ForgeConfigSpec> client = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    private CorpseLabConfig() {
    }

    public static final class Server {

        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.BooleanValue allowManualTrigger;
        public final ForgeConfigSpec.BooleanValue affectCreative;
        public final ForgeConfigSpec.IntValue cooldownTicks;
        public final ForgeConfigSpec.IntValue minGetUpTicks;
        public final ForgeConfigSpec.BooleanValue requireGroundedToGetUp;
        public final ForgeConfigSpec.IntValue groundedTicksBeforeGetUp;
        public final ForgeConfigSpec.DoubleValue maxLaunchSpeed;
        public final ForgeConfigSpec.BooleanValue partSelfCollision;
        public final ForgeConfigSpec.BooleanValue rollEnabled;
        public final ForgeConfigSpec.DoubleValue rollSpeed;
        public final ForgeConfigSpec.DoubleValue rollSpin;

        public final ForgeConfigSpec.BooleanValue grabEnabled;
        public final ForgeConfigSpec.DoubleValue grabReach;
        public final ForgeConfigSpec.DoubleValue grabHoldDistance;
        public final ForgeConfigSpec.DoubleValue grabStrength;
        public final ForgeConfigSpec.DoubleValue grabBreakDistance;
        public final ForgeConfigSpec.IntValue safetyTimeoutTicks;
        public final ForgeConfigSpec.BooleanValue expireWhenSlow;
        public final ForgeConfigSpec.DoubleValue releaseSpeedThreshold;

        public final ForgeConfigSpec.BooleanValue fallEnabled;
        public final ForgeConfigSpec.DoubleValue fallMinHeight;
        public final ForgeConfigSpec.DoubleValue fallMinDamage;
        public final ForgeConfigSpec.DoubleValue fallSlamMultiplier;

        public final ForgeConfigSpec.BooleanValue impactEnabled;
        public final ForgeConfigSpec.DoubleValue impactMinVelocityDelta;
        public final ForgeConfigSpec.DoubleValue impactMaxVelocityDelta;

        public final ForgeConfigSpec.BooleanValue hitEnabled;
        public final ForgeConfigSpec.DoubleValue hitMinDamage;
        public final ForgeConfigSpec.DoubleValue hitLaunchMultiplier;
        public final ForgeConfigSpec.DoubleValue hitProjectileLaunchMultiplier;
        public final ForgeConfigSpec.DoubleValue hitMaxLaunchSpeed;
        public final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> projectileDamageTypes;

        public final ForgeConfigSpec.BooleanValue explosionEnabled;
        public final ForgeConfigSpec.DoubleValue explosionMinPower;
        public final ForgeConfigSpec.DoubleValue explosionRadiusPadding;
        public final ForgeConfigSpec.DoubleValue explosionLaunchMultiplier;

        public final ForgeConfigSpec.BooleanValue lightningEnabled;
        public final ForgeConfigSpec.DoubleValue lightningLaunchSpeed;

        public final ForgeConfigSpec.BooleanValue crashEnabled;
        public final ForgeConfigSpec.DoubleValue crashMinDamage;
        public final ForgeConfigSpec.DoubleValue crashLaunchMultiplier;

        public final ForgeConfigSpec.IntValue suppressionGraceTicks;
        public final ForgeConfigSpec.BooleanValue suppressRiptide;
        public final ForgeConfigSpec.BooleanValue suppressBounce;
        public final ForgeConfigSpec.BooleanValue suppressElytraFlight;
        public final ForgeConfigSpec.BooleanValue suppressCreativeFlight;
        public final ForgeConfigSpec.BooleanValue suppressClimbing;
        public final ForgeConfigSpec.BooleanValue suppressWater;

        public final ForgeConfigSpec.BooleanValue impactDamageEnabled;
        public final ForgeConfigSpec.DoubleValue impactDamageThreshold;
        public final ForgeConfigSpec.DoubleValue impactDamageMultiplier;
        public final ForgeConfigSpec.DoubleValue impactDamageMax;
        public final ForgeConfigSpec.IntValue impactDamageCooldownTicks;

        public final ForgeConfigSpec.BooleanValue corpseEnabled;
        public final ForgeConfigSpec.IntValue corpseLifetimeTicks;
        public final ForgeConfigSpec.BooleanValue corpseDespawnWhenEmpty;
        public final ForgeConfigSpec.IntValue corpseFreezeTicks;

        public final ForgeConfigSpec.BooleanValue mobCorpseEnabled;
        public final ForgeConfigSpec.IntValue mobCorpseLifetimeTicks;
        public final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> mobCorpseTypes;

        public final ForgeConfigSpec.BooleanValue soundEnabled;
        public final ForgeConfigSpec.DoubleValue soundVolume;

        private Server(ForgeConfigSpec.Builder b) {
            b.comment("布娃娃行为，由服务端裁定").push("general");

            enabled = b
                    .comment("总开关，关掉之后任何东西都不会变成布娃娃")
                    .define("enabled", true);

            allowManualTrigger = b
                    .comment("玩家能否用按键把自己变成布娃娃")
                    .define("allowManualTrigger", true);

            affectCreative = b
                    .comment("创造模式玩家是否也会被摔成布娃娃")
                    .define("affectCreative", true);

            cooldownTicks = b
                    .comment("两次摔成布娃娃之间必须等待的 tick 数")
                    .defineInRange("cooldownTicks", 60, 0, 20 * 60);

            minGetUpTicks = b
                    .comment("倒地后至少趴够多少 tick 才能起身")
                    .defineInRange("minGetUpTicks", 60, 0, 20 * 60);

            requireGroundedToGetUp = b
                    .comment("是否必须先真正落地才能起身")
                    .define("requireGroundedToGetUp", true);

            groundedTicksBeforeGetUp = b
                    .comment("身体必须在落地后趴够多少 tick，玩家才能起身",
                            "从落地那一刻开始计，中途又被抛起则重新计时",
                            "这样弹跳中的身体蹭一下地面不算数")
                    .defineInRange("groundedTicksBeforeGetUp", 20, 0, 20 * 60);

            b.pop();
            b.comment("物理上限").push("physics");

            maxLaunchSpeed = b
                    .comment("击飞速度的硬上限，单位为格每秒",
                            "防止大爆炸把身体射出世界之外")
                    .defineInRange("maxLaunchSpeed", 128.0D, 1.0D, 1024.0D);

            partSelfCollision = b
                    .comment("肢体之间是否互相碰撞",
                            "关掉更省 CPU，身体也摊得更平，代价是手臂会插进腿里")
                    .define("partSelfCollision", true);

            b.pop();
            b.comment("玩家变成布娃娃后还能做什么").push("controls");

            rollEnabled = b
                    .comment("倒地时 WASD 能否让身体滚动")
                    .define("rollEnabled", true);

            rollSpeed = b
                    .comment("倒地玩家扭动的速度，单位为格每秒")
                    .defineInRange("rollSpeed", 1.5D, 0.0D, 10.0D);

            rollSpin = b
                    .comment("滚动时附加的自转，单位为弧度每秒",
                            "纯表现：让身体翻滚而不是平移，位移本身",
                            "来自 rollSpeed，因为单靠力矩推不翻趴着的身体")
                    .defineInRange("rollSpin", 3.0D, 0.0D, 50.0D);

            b.pop();
            b.comment("抓起身体并拖着走").push("grab");

            grabEnabled = b
                    .comment("玩家能否抓起倒地的玩家、尸体或死掉的生物")
                    .define("enabled", true);

            grabReach = b
                    .comment("能从多远处抓到一具身体，单位为格")
                    .defineInRange("reach", 4.0D, 1.0D, 16.0D);

            grabHoldDistance = b
                    .comment("被抓住的肢体被拉到抓取者身前多远")
                    .defineInRange("holdDistance", 2.0D, 0.5D, 8.0D);

            grabStrength = b
                    .comment("抓握的拉力，单位为每秒的速率",
                            "越大抓得越牢，过大会让身体被甩来甩去")
                    .defineInRange("strength", 8.0D, 0.5D, 60.0D);

            grabBreakDistance = b
                    .comment("被抓住的肢体可以落后手多远、超过就松手，单位为格",
                            "这条拦住了把身体拖穿墙的情况：",
                            "肢体卡在墙上落后，抓握随即放开")
                    .defineInRange("breakDistance", 2.5D, 0.5D, 32.0D);

            b.pop();
            b.comment("布娃娃自己结束的条件").push("expiry");

            safetyTimeoutTicks = b
                    .comment("一具布娃娃最长能存在的 tick 数，硬性上限",
                            "兜底措施，免得卡住的身体把玩家永久困在地下")
                    .defineInRange("safetyTimeoutTicks", 600, 20, 20 * 600);

            expireWhenSlow = b
                    .comment("身体一停下来就自动让玩家站起来")
                    .define("expireWhenSlow", false);

            releaseSpeedThreshold = b
                    .comment("低于这个速度就算静止，单位为格每秒")
                    .defineInRange("releaseSpeedThreshold", 0.1D, 0.001D, 10.0D);

            b.pop();
            b.comment("什么会把玩家撂倒，每种触发各有自己的阈值").push("triggers");

            b.comment("摔得够狠就倒地").push("fall");
            fallEnabled = b.define("enabled", true);
            fallMinHeight = b
                    .comment("下落达到或超过这么多格就倒地，不管伤害多少",
                            "高度和伤害是两个独立条件，任一满足即可",
                            "只看伤害会漏掉被护甲或缓降减轻的长距离坠落",
                            "只看高度会漏掉摔在尖东西上的短距离下落",
                            "设为 0 表示不看高度，只按伤害判定")
                    .defineInRange("minHeight", 6.0D, 0.0D, 1024.0D);

            fallMinDamage = b
                    .comment("摔落伤害达到或超过这么多就倒地",
                            "设为 0 表示不看伤害，只按高度判定")
                    .defineInRange("minDamage", 4.0D, 0.0D, 1024.0D);
            fallSlamMultiplier = b.comment("落地速度中有多少灌进身体")
                    .defineInRange("slamMultiplier", 0.5D, 0.0D, 10.0D);
            b.pop();

            b.comment("以速度撞上东西，不管伤害多少").push("impact");
            impactEnabled = b.define("enabled", true);
            impactMinVelocityDelta = b
                    .comment("速度变化达到多少格每秒才算撞击")
                    .defineInRange("minVelocityDelta", 16.0D, 0.0D, 1024.0D);
            impactMaxVelocityDelta = b
                    .comment("超过这个值的撞击按这个值处理，免得把人射得离谱")
                    .defineInRange("maxVelocityDelta", 120.0D, 0.0D, 1024.0D);
            b.pop();

            b.comment("挨了一记重击").push("hit");
            hitEnabled = b.define("enabled", true);
            hitMinDamage = b.comment("单次攻击伤害达到或超过这么多就倒地")
                    .defineInRange("minDamage", 8.0D, 0.0D, 1024.0D);
            hitLaunchMultiplier = b
                    .comment("这一击把身体扔出去的力度，按造成的伤害倍乘",
                            "默认值下，10 点伤害的一击会把身体以 15 格每秒扔出去")
                    .defineInRange("launchMultiplier", 1.5D, 0.0D, 100.0D);

            hitProjectileLaunchMultiplier = b
                    .comment("同上，但用于标记为弹射物的伤害：箭，以及正确标记伤害的枪械模组",
                            "子弹的动量远比它造成的伤害小",
                            "所以中枪该让人原地倒下而不是飞出去",
                            "把它设成与 launchMultiplier 相等即可取消这个区别")
                    .defineInRange("projectileLaunchMultiplier", 0.15D, 0.0D, 100.0D);

            hitMaxLaunchSpeed = b
                    .comment("单次攻击能把身体扔到多快的硬性上限，单位为格每秒",
                            "这是安全网：击飞力度随伤害缩放",
                            "否则一把造成 30 点伤害的模组武器会把玩家扔到 45 格每秒",
                            "其他设置都拦不住")
                    .defineInRange("maxLaunchSpeed", 12.0D, 0.0D, 1024.0D);

            projectileDamageTypes = b
                    .comment("额外视作弹射物的伤害类型或伤害类型标签",
                            "枪械模组有自己的伤害类型，大多没有加进",
                            "minecraft:is_projectile，所以只靠原版标签抓不到它们",
                            "每一项可以是伤害类型 id 或标签 id，两种都会尝试",
                            "已对着 jar 核对过：TACZ 用标签 tacz:bullets",
                            "Superb Warfare 用 superbwarfare:gun_damage 和 superbwarfare:projectile")
                    .defineList("projectileDamageTypes",
                            java.util.List.of("tacz:bullets",
                                    "superbwarfare:gun_damage",
                                    "superbwarfare:projectile"),
                            entry -> entry instanceof String);
            b.pop();

            b.comment("被爆炸波及时").push("explosion");
            explosionEnabled = b.define("enabled", true);
            explosionMinPower = b.comment("威力低于这个值的爆炸忽略不计，TNT 是 4")
                    .defineInRange("minPower", 1.0D, 0.0D, 1024.0D);
            explosionRadiusPadding = b
                    .comment("在爆炸范围之外再多出几格仍然会把人撂倒")
                    .defineInRange("radiusPadding", 2.0D, 0.0D, 64.0D);
            explosionLaunchMultiplier = b
                    .comment("爆炸把身体抛出去的力度，按爆炸威力和距离倍乘",
                            "默认值下，站在 TNT 旁边会把身体扔到约 40 格每秒")
                    .defineInRange("launchMultiplier", 10.0D, 0.0D, 1000.0D);
            b.pop();

            b.comment("被闪电击中").push("lightning");
            lightningEnabled = b.define("enabled", true);
            lightningLaunchSpeed = b.comment("向上击飞的速度，单位为格每秒")
                    .defineInRange("launchSpeed", 12.0D, 0.0D, 1024.0D);
            b.pop();

            b.comment("鞘翅飞行时撞上东西").push("elytraCrash");
            crashEnabled = b.define("enabled", true);
            crashMinDamage = b.comment("撞毁伤害达到或超过这么多就倒地")
                    .defineInRange("minDamage", 4.0D, 0.0D, 1024.0D);
            crashLaunchMultiplier = b.comment("飞行速度中有多少带进身体")
                    .defineInRange("launchMultiplier", 3.0D, 0.0D, 100.0D);
            b.pop();

            b.pop();
            b.comment("这些状态下绝不应触发布娃娃",
                    "没有它们，模组就会和游戏本身打架：每次史莱姆弹跳",
                    "每次激流冲刺、每次鞘翅起飞都会把人放倒在地")
                    .push("suppressions");

            suppressionGraceTicks = b
                    .comment("被抑制的状态结束后，还要继续禁用触发多少 tick")
                    .defineInRange("graceTicks", 10, 0, 200);
            suppressRiptide = b.define("riptide", true);
            suppressBounce = b.comment("史莱姆方块和床").define("bounce", true);
            suppressElytraFlight = b.define("elytraFlight", true);
            suppressCreativeFlight = b.define("creativeFlight", true);
            suppressClimbing = b.comment("梯子、藤蔓和脚手架").define("climbing", true);
            suppressWater = b
                    .comment("水。原版里长距离坠落后落进水坑不会受伤",
                            "但入水本身是很大的速度变化",
                            "恰好就是撞击触发要找的东西")
                    .define("water", true);

            b.pop();
            b.comment("被扔进东西里时伤到自己").push("impactDamage");

            impactDamageEnabled = b.define("enabled", true);
            impactDamageThreshold = b
                    .comment("撞击速度超过多少格每秒身体就受伤")
                    .defineInRange("threshold", 12.0D, 0.0D, 1024.0D);
            impactDamageMultiplier = b.defineInRange("multiplier", 0.75D, 0.0D, 100.0D);
            impactDamageMax = b.comment("单次撞击最多造成多少伤害")
                    .defineInRange("max", 20.0D, 0.0D, 1024.0D);
            impactDamageCooldownTicks = b
                    .comment("两次撞击伤害之间的间隔 tick，免得一路滑行变成绞肉机")
                    .defineInRange("cooldownTicks", 10, 0, 200);

            b.pop();
            b.comment("玩家死亡后留下什么").push("corpse");

            corpseEnabled = b
                    .comment("死亡时把身体留在原地，身上带着玩家生前携带的物品",
                            "关掉后按原版方式掉落物品")
                    .define("enabled", true);

            corpseLifetimeTicks = b
                    .comment("尸体存在多少 tick 后自动消失",
                            "还在里面的东西会掉出来而不是被销毁",
                            "0 表示永不过期，一直等着被搜刮")
                    .defineInRange("lifetimeTicks", 20 * 60 * 10, 0, 20 * 60 * 60 * 24);

            corpseDespawnWhenEmpty = b
                    .comment("最后一件物品被取走后立刻移除尸体")
                    .define("despawnWhenEmpty", true);

            corpseFreezeTicks = b
                    .comment("尸体静止多少 tick 后完全停止物理模拟",
                            "已经停稳的身体没有东西需要计算",
                            "满地旧尸体不该各自跑着一个求解器")
                    .defineInRange("freezeTicks", 40, 1, 20 * 60);

            b.pop();
            b.comment("生物死亡后留下的身体，用来替代原版的倒地死亡动画")
                    .push("mobCorpse");

            mobCorpseEnabled = b
                    .comment("列表里的生物死亡时留下一具布娃娃",
                            "掉落不受影响：生物的战利品照常掉在地上")
                    .define("enabled", true);

            mobCorpseLifetimeTicks = b
                    .comment("生物尸体存在多少 tick，0 表示永远存在",
                            "在生物多的地方不建议这么做")
                    .defineInRange("lifetimeTicks", 20 * 30, 0, 20 * 60 * 60);

            mobCorpseTypes = b
                    .comment("哪些生物会留下身体",
                            "只有用原版人形模型绘制的生物才支持，因为布娃娃",
                            "是由该模型的六个长方体搭出来的。服务端看不到",
                            "生物用的是哪个模型，那属于客户端，所以这份列表",
                            "必须显式写明。加进非人形的生物只会留下一具看不见的身体")
                    .defineList("types", java.util.List.of(
                            "minecraft:zombie",
                            "minecraft:husk",
                            "minecraft:drowned",
                            "minecraft:zombie_villager",
                            "minecraft:skeleton",
                            "minecraft:stray",
                            "minecraft:wither_skeleton",
                            "minecraft:piglin",
                            "minecraft:piglin_brute",
                            "minecraft:zombified_piglin",
                            "minecraft:giant"),
                            entry -> entry instanceof String);

            b.pop();
            b.push("sound");
            soundEnabled = b.comment("玩家倒地时播放一声闷响").define("enabled", true);
            soundVolume = b.defineInRange("volume", 1.0D, 0.0D, 1.0D);
            b.pop();

            // 通用骨架尸体系统的设置，与上面的服务器设置同存一份文件
            PlanConfig.define(b);
        }
    }

    public static final class Client {

        public final ForgeConfigSpec.BooleanValue showControlsHint;
        public final ForgeConfigSpec.BooleanValue firstPersonRagdollCamera;
        public final ForgeConfigSpec.BooleanValue hideVanillaDeathAnimation;
        public final ForgeConfigSpec.DoubleValue cameraSmoothing;
        public final ForgeConfigSpec.DoubleValue cameraRoll;

        private Client(ForgeConfigSpec.Builder b) {
            b.comment("表现层，仅客户端").push("display");

            showControlsHint = b
                    .comment("变成布娃娃时在屏幕上显示操作提示")
                    .define("showControlsHint", true);

            firstPersonRagdollCamera = b
                    .comment("第一人称时从布娃娃自己的头部看世界",
                            "相机坐在头里随着它翻滚",
                            "而不是悬在站立时的眼睛高度。关掉则用固定视角")
                    .define("firstPersonRagdollCamera", true);

            hideVanillaDeathAnimation = b
                    .comment("留下身体后隐藏 Minecraft 自带的死亡动画",
                            "原版会继续画出死掉的玩家倒下去，和尸体叠在一起",
                            "同一位置出现两具身体，其中一具还穿过另一具",
                            "如果服务端关掉了尸体，这项也可以关掉")
                    .define("hideVanillaDeathAnimation", true);

            cameraSmoothing = b
                    .comment("布娃娃相机的平滑程度，0 表示硬锁在头上、很抖",
                            "1 表示非常飘。头部是链末端一个小小的轻质刚体",
                            "所以需要一些平滑，免得求解的每次抖动都传到屏幕上")
                    .defineInRange("cameraSmoothing", 0.65D, 0.0D, 1.0D);

            cameraRoll = b
                    .comment("相机跟随头部翻滚的程度，从 0 到 1",
                            "完全跟随虽然准确，但看久了相当晕")
                    .defineInRange("cameraRoll", 0.5D, 0.0D, 1.0D);

            b.pop();
        }
    }
}
