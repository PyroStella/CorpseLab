package com.pyrostella.corpselab.config;

import com.pyrostella.corpselab.plan.PlanConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

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

        public final ForgeConfigSpec.DoubleValue maxLaunchSpeed;
        public final ForgeConfigSpec.BooleanValue partSelfCollision;

        public final ForgeConfigSpec.BooleanValue grabEnabled;
        public final ForgeConfigSpec.DoubleValue grabReach;
        public final ForgeConfigSpec.DoubleValue grabHoldDistance;
        public final ForgeConfigSpec.DoubleValue grabStrength;
        public final ForgeConfigSpec.DoubleValue grabBreakDistance;

        public final ForgeConfigSpec.DoubleValue releaseSpeedThreshold;

        public final ForgeConfigSpec.BooleanValue corpseEnabled;
        public final ForgeConfigSpec.IntValue corpseLifetimeTicks;
        public final ForgeConfigSpec.BooleanValue corpseDespawnWhenEmpty;
        public final ForgeConfigSpec.IntValue corpseFreezeTicks;
        public final ForgeConfigSpec.DoubleValue corpseExplosionLaunchMultiplier;

        public final ForgeConfigSpec.BooleanValue mobCorpseEnabled;
        public final ForgeConfigSpec.IntValue mobCorpseLifetimeTicks;
        public final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> mobCorpseTypes;

        private Server(ForgeConfigSpec.Builder b) {
            b.comment("尸体物理上限").push("physics");

            maxLaunchSpeed = b
                    .comment("击飞速度的硬上限，单位为格每秒", "防止大爆炸把尸体射出世界之外")
                    .defineInRange("maxLaunchSpeed", 128.0D, 1.0D, 1024.0D);

            partSelfCollision = b
                    .comment("肢体之间是否互相碰撞")
                    .define("partSelfCollision", true);

            b.pop();
            b.comment("抓起尸体并拖着走").push("grab");

            grabEnabled = b
                    .comment("玩家能否抓起尸体")
                    .define("enabled", true);

            grabReach = b
                    .comment("能从多远处抓到一具尸体，单位为格")
                    .defineInRange("reach", 4.0D, 1.0D, 16.0D);

            grabHoldDistance = b
                    .comment("被抓住的肢体被拉到抓取者身前多远")
                    .defineInRange("holdDistance", 2.0D, 0.5D, 8.0D);

            grabStrength = b
                    .comment("抓握的拉力，单位为每秒的速率")
                    .defineInRange("strength", 8.0D, 0.5D, 60.0D);

            grabBreakDistance = b
                    .comment("被抓住的肢体可以落后手多远、超过就松手，单位为格")
                    .defineInRange("breakDistance", 2.5D, 0.5D, 32.0D);

            b.pop();
            b.comment("尸体静止判定").push("expiry");

            releaseSpeedThreshold = b
                    .comment("低于这个速度就算静止，单位为格每秒")
                    .defineInRange("releaseSpeedThreshold", 0.1D, 0.001D, 10.0D);

            b.pop();
            // 保留旧路径，避免已有服务器的尸体爆炸力度配置失效
            b.comment("爆炸致死时尸体的初速度").push("triggers");
            b.push("explosion");

            corpseExplosionLaunchMultiplier = b
                    .comment("爆炸致死时尸体向外飞出的力度")
                    .defineInRange("launchMultiplier", 10.0D, 0.0D, 1000.0D);

            b.pop();
            b.pop();
            b.comment("玩家死亡后留下什么").push("corpse");

            corpseEnabled = b
                    .comment("死亡时把身体留在原地，身上带着玩家生前携带的物品", "关掉后按原版方式掉落物品")
                    .define("enabled", true);

            corpseLifetimeTicks = b
                    .comment("尸体存在多少 tick 后自动消失")
                    .defineInRange("lifetimeTicks", 20 * 60 * 10, 0, 20 * 60 * 60 * 24);

            corpseDespawnWhenEmpty = b
                    .comment("最后一件物品被取走后立刻移除尸体")
                    .define("despawnWhenEmpty", true);

            corpseFreezeTicks = b
                    .comment("尸体静止多少 tick 后完全停止物理模拟")
                    .defineInRange("freezeTicks", 40, 1, 20 * 60);

            b.pop();
            b.comment("生物死亡后留下的身体，用来取代原版的倒地死亡动画")
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
                    .defineList("types", List.of(
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

            // 通用骨架尸体系统的设置，与上面的服务器设置同存一份文件
            PlanConfig.define(b);
        }
    }

    public static final class Client {

        public final ForgeConfigSpec.BooleanValue hideVanillaDeathAnimation;

        private Client(ForgeConfigSpec.Builder b) {
            b.comment("表现层，仅客户端").push("display");

            hideVanillaDeathAnimation = b
                    .comment("留下身体后隐藏 Minecraft 自带的死亡动画")
                    .define("hideVanillaDeathAnimation", true);

            b.pop();
        }
    }
}

