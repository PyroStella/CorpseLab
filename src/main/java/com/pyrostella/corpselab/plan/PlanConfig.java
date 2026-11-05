package com.pyrostella.corpselab.plan;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 通用尸体系统的配置。
 *
 * <p>单独一份 spec，不塞进从 Tumble 继承的那份大配置里，避免改动原有逻辑。</p>
 */
public final class PlanConfig {

    /** 布娃娃形态 */
    public enum PlanMode {
        /**
         * 铰接：部件之间建软关节，平时连成一体，被重击拽开太远会断开。
         */
        ARTICULATED,
        /**
         * 自由：一个关节都不建，每个部件都是独立刚体，只靠互相碰撞和地面堆成一摊。
         */
        FREE
    }

    private static final ForgeConfigSpec.EnumValue<PlanMode> MODE;
    private static final ForgeConfigSpec.IntValue MAX_AGE_TICKS;
    private static final ForgeConfigSpec.DoubleValue ACTIVE_RADIUS;
    private static final ForgeConfigSpec.IntValue MAX_PER_AREA;
    private static final ForgeConfigSpec.BooleanValue SELF_COLLISION;
    private static final ForgeConfigSpec.IntValue SOLVER_ITERATIONS;
    private static final ForgeConfigSpec.DoubleValue JOINT_COMPLIANCE;
    private static final ForgeConfigSpec.DoubleValue JOINT_BREAK;

    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("plan_corpse");
        MODE = builder
                .comment("布娃娃形态。",
                        "ARTICULATED = 有骨架：部件之间是软关节，平时连成一体，被重击拽开太远会断开。",
                        "FREE = 无骨架：一个关节都不建，部件完全独立，只靠互相碰撞和地面堆成一摊。")
                .defineEnum("mode", PlanMode.ARTICULATED);

        MAX_AGE_TICKS = builder
                .comment("尸体存在多少 tick 后消失。20 tick = 1 秒，默认 2400 = 2 分钟。")
                .defineInRange("maxAgeTicks", 2400, 20, 240000);

        ACTIVE_RADIUS = builder
                .comment("多少格内有玩家才跑物理。远处的尸体冻结，省 CPU；玩家走近会接着模拟。")
                .defineInRange("activeRadius", 64.0D, 8.0D, 256.0D);

        MAX_PER_AREA = builder
                .comment("同一片区域最多保留多少具尸体，超出时删最老的。")
                .defineInRange("maxCorpses", 32, 1, 512);

        SELF_COLLISION = builder
                .comment("部件之间是否互相碰撞（手臂不能插进胸腔、腿不能穿过另一条腿）。",
                        "关掉可以省一点 CPU，代价是肢体可能互相穿插。")
                .define("selfCollision", true);

        SOLVER_ITERATIONS = builder
                .comment("每个物理子步里把所有约束反复求解多少遍。",
                        "1 = 只投一遍：互相矛盾的部分留成残余位移，再被速度推导折算成抖动。",
                        "调大能压掉抽搐，代价是 CPU。默认 6。")
                .defineInRange("solverIterations", 6, 1, 16);

        JOINT_COMPLIANCE = builder
                .comment("ARTICULATED 模式关节的柔软度。0 = 部件被焊死；调大，关节会被自重和冲击拉开。",
                        "实测关节缝隙：0 -> 1mm，0.002 -> 10mm，0.01 -> 17mm（静置）。默认 0.002。")
                .defineInRange("jointCompliance", 0.002D, 0.0D, 0.01D);

        JOINT_BREAK = builder
                .comment("ARTICULATED 模式关节被拉开多少格就彻底断开，那截肢体真的掉下来。0 = 永不断开。",
                        "实测静置空隙约 10mm、正常坠落约 17mm、被 12 m/s 撞飞时峰值约 46mm，",
                        "默认 0.03：普通倒下还连着，挨了重击才掉件。")
                .defineInRange("jointBreakDistance", 0.03D, 0.0D, 2.0D);
        builder.pop();

        SPEC = builder.build();
    }

    private PlanConfig() {
    }

    public static PlanMode mode() {
        return MODE.get();
    }

    public static int maxAgeTicks() {
        return MAX_AGE_TICKS.get();
    }

    public static double activeRadius() {
        return ACTIVE_RADIUS.get();
    }

    public static int maxPerArea() {
        return MAX_PER_AREA.get();
    }

    public static boolean selfCollision() {
        return SELF_COLLISION.get();
    }

    public static int solverIterations() {
        return SOLVER_ITERATIONS.get();
    }

    public static double jointCompliance() {
        return JOINT_COMPLIANCE.get();
    }

    public static double jointBreakDistance() {
        return JOINT_BREAK.get();
    }
}
