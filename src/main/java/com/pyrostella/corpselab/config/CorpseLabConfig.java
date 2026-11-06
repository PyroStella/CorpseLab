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
                    .defineInRange("strength", 16.0D, 0.5D, 60.0D);

            grabBreakDistance = b
                    .comment("被抓住的肢体可以落后手多远、超过就松手，单位为格")
                    .defineInRange("breakDistance", 2.5D, 0.5D, 32.0D);

            b.pop();
            b.comment("尸体静止判定").push("expiry");

            releaseSpeedThreshold = b
                    .comment("低于这个速度就算静止，单位为格每秒")
                    .defineInRange("releaseSpeedThreshold", 0.1D, 0.001D, 10.0D);

            b.pop();
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
            // 通用骨架尸体系统的设置，与上面的服务器设置同存一份文件
            PlanConfig.define(b);
        }
    }

    public static final class Client {

        public final ForgeConfigSpec.BooleanValue hideVanillaDeathAnimation;
        public final ForgeConfigSpec.BooleanValue collisionBoxDisplay;

        private Client(ForgeConfigSpec.Builder b) {
            b.comment("表现层，仅客户端").push("display");

            hideVanillaDeathAnimation = b
                    .comment("留下身体后隐藏 Minecraft 自带的死亡动画")
                    .define("hideVanillaDeathAnimation", true);

            collisionBoxDisplay = b
                    .comment("显示尸体肢体碰撞箱，仅影响本机")
                    .define("collisionBoxDisplay", false);

            b.pop();
        }
    }
}

